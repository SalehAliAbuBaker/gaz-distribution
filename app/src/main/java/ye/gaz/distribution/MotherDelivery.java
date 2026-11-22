package ye.gaz.distribution;
import org.json.*;
import java.util.*;
import java.time.Instant;

/** The mother retains immutable signed receipts. Administrative corrections are separate.
 * A live offline grant is never released until its child confirms it is locked. */
final class MotherDelivery {
    static JSONObject control(JSONObject s,String k)throws Exception{return SyncRules.map(s,"deliveryControls").optJSONObject(k);}
    static boolean held(JSONObject s,String k)throws Exception{JSONObject c=control(s,k);return c!=null&&c.optBoolean("locked");}
    static boolean resolved(JSONObject s,String k)throws Exception{JSONObject c=control(s,k);return SyncRules.map(s,"receipts").has(k)||(c!=null&&c.optBoolean("returned"));}
    static void audit(JSONObject s,String k,String actor,String reason,String action,JSONObject before,JSONObject after)throws Exception{
        JSONArray log=s.optJSONArray("deliveryAudit");if(log==null){log=new JSONArray();s.put("deliveryAudit",log);}
        log.put(new JSONObject().put("key",k).put("action",action).put("actor",actor).put("reason",reason).put("at",Instant.now().toString()).put("before",before==null?JSONObject.NULL:SyncRules.copy(before)).put("after",after==null?JSONObject.NULL:SyncRules.copy(after)));
    }
    static void change(JSONObject s,JSONObject local,String k,String op,int quantity,String actor,String reason)throws Exception{
        SyncRules.require("mother".equals(s.getString("role")),"Mother required");
        reason=reason.trim();SyncRules.require(!reason.isEmpty()&&reason.length()<=500,"Reason required");
        JSONObject grant=SyncWire.peek(SyncRules.map(s,"grants").getString(k));
        String cycle=grant.getString("cycle"),family=grant.getString("family");
        JSONObject c=SyncRules.find(local.getJSONArray("cycles"),cycle),f=SyncRules.find(local.getJSONArray("families"),family);
        SyncRules.require(c!=null&&f!=null&&!c.optBoolean("closed"),"Open cycle required");
        JSONObject old=SyncRules.delivery(local,cycle,family),next=null,prior=control(s,k);
        JSONObject policy=prior==null?new JSONObject():SyncRules.copy(prior);
        policy.put("locked",true);
        if("recall".equals(op)){
            SyncRules.require(!resolved(s,k)&&prior==null,"Already recalled or settled");
        }else{
            SyncRules.require(resolved(s,k),"Return allocation first");
            if("delete".equals(op)){SyncRules.require(old!=null,"No delivery");}
            else if("edit".equals(op)||"add".equals(op)){
                SyncRules.require("edit".equals(op)?old!=null:old==null,"Invalid delivery state");SyncRules.quantity(quantity);
                next=old==null?new JSONObject().put("c",cycle).put("f",family).put("at",Instant.now().toString()).put("by",actor):SyncRules.copy(old);
                next.put("q",quantity).put("updatedAt",Instant.now().toString()).put("updatedBy",actor).put("motherCorrected",true);
                next.remove("valueKind");next.remove("currency");next.remove("unitMinor");next.remove("amountMinor");
                RafdMoney.attach(next,c,quantity);
            }else throw new IllegalArgumentException("Unknown action");
            JSONArray rows=local.getJSONArray("deliveries");
            for(int i=rows.length()-1;i>=0;i--){JSONObject r=rows.getJSONObject(i);if(cycle.equals(r.getString("c"))&&family.equals(r.getString("f")))rows.remove(i);}
            if(next!=null)rows.put(next);
            policy.put("corrected",true);
        }
        policy.put("revision",Math.addExact(policy.optLong("revision",0),1)).put("actor",actor).put("reason",reason);
        SyncRules.map(s,"deliveryControls").put(k,policy);audit(s,k,actor,reason,op,old,next);
    }
    static int batch(JSONObject s,JSONObject local,String cycle,String op,JSONObject requested,String actor,String reason)throws Exception{
        SyncRules.require("mother".equals(s.getString("role")),"Mother required");
        JSONObject c=SyncRules.find(local.getJSONArray("cycles"),cycle);SyncRules.require(c!=null&&!c.optBoolean("closed"),"Open cycle required");
        reason=reason.trim();SyncRules.require(!reason.isEmpty()&&reason.length()<=500,"Reason required");
        Map<String,Integer> tasks=new LinkedHashMap<>();
        if("delete".equals(op)){JSONArray rows=local.getJSONArray("deliveries");for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if(cycle.equals(r.getString("c")))tasks.put(r.getString("f"),0);}}
        else if("add".equals(op)){for(Iterator<String> it=requested.keys();it.hasNext();){String family=it.next();PairPackets.uuid(family);tasks.put(family,SyncRules.quantity(requested.get(family)));}}
        else throw new IllegalArgumentException("Unknown batch action");
        SyncRules.require(!tasks.isEmpty()&&tasks.size()<=20000,"Invalid selection");
        JSONObject grants=SyncRules.map(s,"grants");JSONArray rows=local.getJSONArray("deliveries");
        for(Map.Entry<String,Integer> e:tasks.entrySet()){
            String family=e.getKey(),k=SyncRules.key(cycle,family);
            SyncRules.require(SyncRules.find(local.getJSONArray("families"),family)!=null,"Missing family");
            JSONObject old=SyncRules.delivery(local,cycle,family);SyncRules.require("delete".equals(op)?old!=null:old==null,"Delivery changed");
            if(grants.has(k)){change(s,local,k,op,e.getValue(),actor,reason);continue;}
            if("delete".equals(op)){for(int i=rows.length()-1;i>=0;i--){JSONObject row=rows.getJSONObject(i);if(cycle.equals(row.getString("c"))&&family.equals(row.getString("f")))rows.remove(i);}audit(s,k,actor,reason,op,old,null);}
            else{JSONObject row=new JSONObject().put("c",cycle).put("f",family).put("q",e.getValue()).put("by",actor).put("at",Instant.now().toString()).put("motherCorrected",true);RafdMoney.attach(row,c,e.getValue());rows.put(row);audit(s,k,actor,reason,op,null,row);}
        }
        return tasks.size();
    }
    static void acceptReturns(JSONObject s,String device,JSONObject p,boolean policyOnly)throws Exception{
        if(policyOnly||!p.has("returns"))return;JSONArray a=p.getJSONArray("returns");SyncRules.require(a.length()<=20000,"Too many returns");
        for(int i=0;i<a.length();i++){
            String k=a.getString(i);JSONObject c=control(s,k);JSONObject g=SyncWire.peek(SyncRules.map(s,"grants").getString(k));
            SyncRules.require(device.equals(g.getString("device"))&&c!=null&&c.optBoolean("locked"),"Invalid return");
            if(!c.optBoolean("returned")){c.put("returned",true);audit(s,k,"الموزع","تأكيد إيقاف صلاحية التسليم","returned",null,null);}
        }
    }
    static JSONObject response(JSONObject s,JSONObject local,String k)throws Exception{
        JSONObject c=control(s,k);if(c==null)return null;
        JSONObject g=SyncWire.peek(SyncRules.map(s,"grants").getString(k)),d=SyncRules.delivery(local,g.getString("cycle"),g.getString("family"));
        JSONObject p=new JSONObject().put("locked",true).put("revision",c.getLong("revision")).put("returned",c.optBoolean("returned")).put("corrected",c.optBoolean("corrected"));
        if(c.optBoolean("corrected"))p.put("delivery",d==null?JSONObject.NULL:new JSONObject().put("q",d.getInt("q")).put("at",d.getString("at")).put("operator",d.optString("updatedBy",d.optString("by"))));
        return p;
    }
    static void acceptControls(JSONObject s,JSONObject p)throws Exception{
        if(!p.has("deliveryControls"))return;JSONObject incoming=p.getJSONObject("deliveryControls"),known=SyncRules.map(s,"grants");
        SyncRules.require(incoming.length()<=20000,"Too many controls");
        for(Iterator<String> it=incoming.keys();it.hasNext();){String k=it.next();JSONObject n=incoming.getJSONObject(k),old=control(s,k);
            SyncRules.require(known.has(k)&&n.getBoolean("locked")&&n.getLong("revision")>0&&(old==null||n.getLong("revision")>=old.getLong("revision")),"Invalid control");
            if(n.optBoolean("corrected")&&!n.isNull("delivery"))SyncRules.quantity(n.getJSONObject("delivery").get("q"));
            SyncRules.map(s,"deliveryControls").put(k,n);
        }
    }
    static JSONArray returns(JSONObject s)throws Exception{
        JSONArray a=new JSONArray();JSONObject c=SyncRules.map(s,"deliveryControls"),out=SyncRules.map(s,"outbox"),acked=SyncRules.map(s,"acked");
        for(Iterator<String> it=c.keys();it.hasNext();){String k=it.next();if(c.getJSONObject(k).optBoolean("locked")&&(!out.has(k)||acked.optBoolean(k)))a.put(k);}
        return a;
    }
}
