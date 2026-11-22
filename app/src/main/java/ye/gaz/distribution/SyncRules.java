package ye.gaz.distribution;

import org.json.*;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.*;

/** SQLite caller commits the pair state and delivery record in one transaction. */
final class SyncRules {
    static void require(boolean value,String reason)throws Exception{PairPackets.require(value,reason);}
    static JSONObject copy(JSONObject x)throws Exception{return new JSONObject(x.toString());}
    static JSONObject map(JSONObject s,String k)throws Exception{JSONObject x=s.optJSONObject(k);if(x==null){x=new JSONObject();s.put(k,x);}return x;}
    static JSONObject find(JSONArray a,String id)throws Exception{for(int i=0;i<a.length();i++){JSONObject x=a.getJSONObject(i);if(id.equals(x.getString("id")))return x;}return null;}
    static JSONObject delivery(JSONObject local,String cycle,String family)throws Exception{JSONArray a=local.getJSONArray("deliveries");for(int i=0;i<a.length();i++){JSONObject x=a.getJSONObject(i);if(cycle.equals(x.getString("c"))&&family.equals(x.getString("f")))return x;}return null;}
    static String key(String cycle,String family)throws Exception{PairPackets.uuid(cycle);PairPackets.uuid(family);return cycle+"/"+family;}
    static int quantity(Object x)throws Exception{require(x instanceof Number,"Invalid quantity");double n=((Number)x).doubleValue();require(n==Math.floor(n)&&n>=1&&n<=10000,"Invalid quantity");return (int)n;}
    static JSONObject base(JSONObject s,String type,String device)throws Exception{
        return new JSONObject().put("v",1).put("type",type).put("deployment",s.getString("deployment")).put("epoch",s.getString("epoch")).put("mother","mother".equals(s.getString("role"))?s.getString("device"):s.getString("mother")).put("device",device);
    }
    static void context(JSONObject s,JSONObject p,String device)throws Exception{
        JSONObject expected=base(s,p.getString("type"),device);for(String k:new String[]{"deployment","epoch","mother","device"})require(expected.getString(k).equals(p.getString(k)),"Wrong device or neighborhood");
    }
    static JSONObject child(JSONObject s,String d)throws Exception{PairPackets.uuid(d);JSONObject x=s.getJSONObject("devices").optJSONObject(d);require(x!=null,"Unknown distributor");return x;}
    static String grant(JSONObject state,JSONObject local,String device,String cycle,String family,PrivateKey secret)throws Exception{
        require("mother".equals(state.getString("role")),"Mother required");child(state,device);
        JSONObject c=find(local.getJSONArray("cycles"),cycle),f=find(local.getJSONArray("families"),family);
        require(c!=null&&f!=null&&!c.optBoolean("closed")&&delivery(local,cycle,family)==null,"Unavailable family/cycle");
        String k=key(cycle,family);JSONObject grants=map(state,"grants");require(!grants.has(k),"Already allocated");require(grants.length()<20000,"Test grant limit");
        JSONObject p=base(state,"grant",device).put("id",UUID.randomUUID().toString()).put("cycle",cycle).put("family",family).put("maxQty",quantity(Integer.valueOf(AidQuota.forFamily(c,f)))).put("cycleTitle",c.getString("title")).put("cycleDate",c.getString("date")).put("name",f.getString("name")).put("house",f.getString("house")).put("no",f.getInt("no")).put("square",f.optString("square",""));
        RafdMoney.checkCycle(c);if(!RafdMoney.kind(c).equals("none")&&RafdMoney.unit(c)>0){p.put("valueKind",RafdMoney.kind(c)).put("currency",RafdMoney.currency(c)).put("unitMinor",RafdMoney.unit(c));}
        String packet=SyncWire.sign(p,secret);grants.put(k,packet);return packet;
    }
    static JSONObject grantPayload(JSONObject s,String packet,String device)throws Exception{
        JSONObject p=SyncWire.verify(packet,"mother".equals(s.getString("role"))?s.getString("pub"):s.getString("motherPub"),"grant");context(s,p,device);
        PairPackets.uuid(p.getString("id"));key(p.getString("cycle"),p.getString("family"));quantity(p.get("maxQty"));return p;
    }
    static void acceptGrant(JSONObject s,String packet)throws Exception{
        String device=s.getString("device");JSONObject p=grantPayload(s,packet,device);
        JSONObject grants=map(s,"grants");String k=key(p.getString("cycle"),p.getString("family"));
        require(!grants.has(k)||packet.equals(grants.getString(k)),"Conflicting grant");grants.put(k,packet);
    }
    static String record(JSONObject s,String k,int q,PrivateKey secret)throws Exception{return record(s,k,q,secret,null,null);}
    static String record(JSONObject s,String k,int q,PrivateKey secret,String operatorId,String operator)throws Exception{
        require("distributor".equals(s.getString("role")),"Distributor required");String device=s.getString("device");
        JSONObject p=grantPayload(s,map(s,"grants").getString(k),device);JSONObject out=map(s,"outbox");
        require(!out.has(k)&&!MotherDelivery.held(s,k),"Already delivered or recalled");require(quantity(q)<=p.getInt("maxQty"),"Quantity exceeds grant");
        JSONObject receipt=base(s,"receipt",device).put("id",UUID.randomUUID().toString()).put("grant",p.getString("id")).put("cycle",p.getString("cycle")).put("family",p.getString("family")).put("q",q).put("at",Instant.now().toString());
        if(operatorId!=null){PairPackets.uuid(operatorId);require(operator!=null&&!operator.trim().isEmpty()&&operator.length()<=120,"Invalid operator");receipt.put("operatorId",operatorId).put("operator",operator);}
        String packet=SyncWire.sign(receipt,secret);out.put(k,packet);return packet;
    }
    static void importReceipt(JSONObject s,JSONObject local,String device,String packet)throws Exception{
        JSONObject user=child(s,device);JSONObject p=SyncWire.verify(packet,user.getString("pub"),"receipt");context(s,p,device);
        String cycle=p.getString("cycle"),family=p.getString("family"),k=key(cycle,family);PairPackets.uuid(p.getString("id"));Instant.parse(p.getString("at"));int q=quantity(p.get("q"));
        JSONObject g=grantPayload(s,map(s,"grants").getString(k),device);
        require(g.getString("id").equals(p.getString("grant"))&&q<=g.getInt("maxQty"),"Invalid allocation");
        JSONObject receipts=map(s,"receipts");
        if(receipts.has(k)){require(packet.equals(receipts.getString(k)),"Conflicting receipt");return;}
        JSONObject control=MotherDelivery.control(s,k);require(control==null||!control.optBoolean("returned"),"Allocation already returned");
        for(Iterator<String> it=receipts.keys();it.hasNext();){JSONObject old=SyncWire.peek(receipts.getString(it.next()));require(!old.getString("id").equals(p.getString("id")),"Receipt reused");}
        JSONObject c=find(local.getJSONArray("cycles"),cycle);require(c!=null&&!c.optBoolean("closed")&&find(local.getJSONArray("families"),family)!=null&&delivery(local,cycle,family)==null,"Local record conflict");
        JSONObject movement=new JSONObject().put("c",cycle).put("f",family).put("q",q).put("at",p.getString("at")).put("by",user.getString("name")).put("syncReceipt",p.getString("id")).put("syncDevice",device);
        if(p.has("operator")||p.has("operatorId")){String operator=p.getString("operator"),operatorId=p.getString("operatorId");PairPackets.uuid(operatorId);require(!operator.trim().isEmpty()&&operator.length()<=120,"Invalid operator");movement.put("syncOperator",operator).put("syncOperatorId",operatorId).put("by",user.getString("name")+" / "+operator);}
        RafdMoney.attach(movement,g,q);
        local.getJSONArray("deliveries").put(movement);
        receipts.put(k,packet);
    }
    static JSONObject request(JSONObject s,String challenge,PrivateKey secret)throws Exception{
        PairPackets.uuid(challenge);JSONObject p=base(s,"sync",s.getString("device")).put("challenge",challenge);JSONArray receipts=new JSONArray();JSONObject out=map(s,"outbox"),acked=map(s,"acked");
        for(Iterator<String> it=out.keys();it.hasNext();){String k=it.next();if(!acked.optBoolean(k))receipts.put(out.getString(k));}
        return p.put("receipts",receipts).put("deliveryProtocol",1).put("returns",MotherDelivery.returns(s));
    }
    static JSONObject respond(JSONObject s,JSONObject local,String request,String challenge)throws Exception{
        JSONObject peek=SyncWire.peek(request);String device=peek.getString("device");JSONObject user=child(s,device);
        JSONObject p=SyncWire.verify(request,user.getString("pub"),"sync");context(s,p,device);require(challenge.equals(p.getString("challenge")),"Stale challenge");
        CentralUsers.observe(s,device,p);boolean policyOnly=p.optBoolean("policyOnly",false);JSONArray incoming=p.getJSONArray("receipts");require(incoming.length()<=20000&&(!policyOnly||incoming.length()==0),"Invalid receipts");
        for(int i=0;i<incoming.length();i++)importReceipt(s,local,device,incoming.getString(i));
        MotherDelivery.acceptReturns(s,device,p,policyOnly);
        JSONArray grants=new JSONArray(),acks=new JSONArray();JSONObject g=map(s,"grants"),r=map(s,"receipts"),squares=new JSONObject(),houses=new JSONObject(),controls=new JSONObject();
        if(!policyOnly)for(Iterator<String> it=g.keys();it.hasNext();){String k=it.next(),packet=g.getString(k);JSONObject gp=SyncWire.peek(packet);
            if(device.equals(gp.getString("device"))){grants.put(packet);JSONObject family=find(local.getJSONArray("families"),gp.getString("family"));require(family!=null,"Missing allocated family");squares.put(k,family.optString("square",""));houses.put(k,family.optString("house",""));if(r.has(k))acks.put(SyncWire.peek(r.getString(k)).getString("id"));JSONObject ctrl=MotherDelivery.response(s,local,k);if(ctrl!=null)controls.put(k,ctrl);}}
        JSONObject result=base(s,"result",device).put("challenge",challenge).put("grants",grants).put("acks",acks).put("squares",squares).put("houses",houses).put("deliveryControls",controls);JSONObject accounts=CentralUsers.policy(s,device);if(accounts!=null)result.put("accountPolicy",accounts);return result;
    }
    static void acceptResponse(JSONObject s,String packet,String challenge)throws Exception{
        JSONObject p=SyncWire.verify(packet,s.getString("motherPub"),"result");context(s,p,s.getString("device"));require(challenge.equals(p.getString("challenge")),"Stale response");
        CentralUsers.accept(s,p);JSONArray grants=p.getJSONArray("grants"),acks=p.getJSONArray("acks");require(grants.length()<=20000&&acks.length()<=20000,"Too many records");
        for(int i=0;i<grants.length();i++)acceptGrant(s,grants.getString(i));
        MotherDelivery.acceptControls(s,p);
        if(p.has("houses")){JSONObject incoming=p.getJSONObject("houses"),known=map(s,"grants"),labels=map(s,"houseLabels");for(Iterator<String> it=incoming.keys();it.hasNext();){String k=it.next();Object value=incoming.get(k);require(known.has(k)&&value instanceof String&&((String)value).length()<=200,"Invalid family link");labels.put(k,value);}}
        if(p.has("squares")){JSONObject incoming=p.getJSONObject("squares"),known=map(s,"grants"),labels=map(s,"squareLabels");for(Iterator<String> it=incoming.keys();it.hasNext();){String k=it.next();Object value=incoming.get(k);require(known.has(k)&&value instanceof String&&((String)value).length()<=80,"Invalid square label");labels.put(k,value);}}
        Set<String> ids=new HashSet<>();for(int i=0;i<acks.length();i++)ids.add(acks.getString(i));
        JSONObject out=map(s,"outbox"),accepted=map(s,"acked");
        for(Iterator<String> it=out.keys();it.hasNext();){String k=it.next();if(ids.contains(SyncWire.peek(out.getString(k)).getString("id")))accepted.put(k,true);}
    }
    // Guard web writes against stale snapshots and assignment bypasses.
    static void protectMother(JSONObject s,JSONObject old,JSONObject next)throws Exception{
        JSONObject grants=map(s,"grants"),receipts=map(s,"receipts");
        for(Iterator<String> it=grants.keys();it.hasNext();){String k=it.next();JSONObject p=SyncWire.peek(grants.getString(k));String c=p.getString("cycle"),f=p.getString("family");
            JSONObject before=find(old.getJSONArray("cycles"),c),after=find(next.getJSONArray("cycles"),c);
            JSONObject fb=find(old.getJSONArray("families"),f),fa=find(next.getJSONArray("families"),f);
            require(before!=null&&after!=null&&fb!=null&&fa!=null,"Allocated cycle/family cannot be removed");
            for(String field:new String[]{"title","date","quota"})require(String.valueOf(before.get(field)).equals(String.valueOf(after.get(field))),"Allocated cycle cannot be changed");
            for(String field:new String[]{"valueKind","currency","unitMinor"})require(String.valueOf(before.opt(field)).equals(String.valueOf(after.opt(field))),"Allocated bag value cannot be changed");
            for(String field:new String[]{"aidTwoAbove","aidThreeAbove"})require(AidQuota.threshold(before,field)==AidQuota.threshold(after,field),"Allocated cycle aid rules cannot be changed");
            for(String field:new String[]{"name","no"})require(String.valueOf(fb.get(field)).equals(String.valueOf(fa.get(field))),"Allocated family cannot be changed");
            require(!after.optBoolean("closed")||MotherDelivery.resolved(s,k),"Outstanding allocation");
            JSONObject prev=delivery(old,c,f),cur=delivery(next,c,f);
            if(MotherDelivery.resolved(s,k)){require(CentralUsers.same(prev,cur),"Synchronized delivery protected");}
            else require(prev==null&&cur==null,"Assigned to distributor");
        }
    }
}
