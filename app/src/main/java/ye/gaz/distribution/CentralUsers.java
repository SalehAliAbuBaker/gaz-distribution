package ye.gaz.distribution;
import org.json.*;
import java.util.*;

/** Central per-device account policy. Existing local password hashes are never exported. */
final class CentralUsers {
    static final String[] PERMS={"view","deliver","history","reports","share","sync"};
    static final String[] LABELS={"عرض الأسر المخصصة","تسجيل التسليم","سجل التسليم","الكشوف والتقارير","مشاركة الكشوف","مزامنة التوزيع"};
    static final int MAX=100;
    static long rev(JSONObject p){return p==null?0:p.optLong("revision",0);}
    static long revision(JSONObject s){return rev(s.optJSONObject("centralAccounts"));}
    static JSONObject policy(JSONObject s,String device)throws Exception{return SyncRules.map(s,"accountPolicies").optJSONObject(device);}
    static JSONObject status(JSONObject s,String device)throws Exception{return SyncRules.map(s,"accountStatus").optJSONObject(device);}
    static JSONArray validate(JSONArray raw)throws Exception{
        SyncRules.require(raw.length()<=MAX,"Too many accounts");JSONArray out=new JSONArray();Set<String> ids=new HashSet<>(),names=new HashSet<>();
        for(int i=0;i<raw.length();i++){
            JSONObject u=raw.getJSONObject(i);String id=u.getString("id"),name=u.getString("username").trim();PairPackets.uuid(id);
            SyncRules.require(!name.isEmpty()&&name.length()<=80&&ids.add(id)&&names.add(DistributorUsers.normalize(name)),"Invalid account");
            JSONObject p=new JSONObject();for(String k:PERMS)p.put(k,u.getJSONObject("permissions").optBoolean(k));
            if(p.optBoolean("share"))p.put("reports",true);p.put("admin",false);
            JSONObject a=new JSONObject().put("id",id).put("username",name).put("permissions",p).put("enabled",u.optBoolean("enabled",true));
            if(u.has("hash")||u.has("salt")){String salt=u.getString("salt"),hash=u.getString("hash");PairPackets.uuid(salt);SyncRules.require(hash.matches("[0-9a-f]{64}"),"Invalid verifier");a.put("salt",salt).put("hash",hash);}
            out.put(a);
        }return out;
    }
    static JSONArray roster(JSONArray local)throws Exception{
        JSONArray a=new JSONArray();for(int i=0;i<local.length();i++){JSONObject u=local.getJSONObject(i),p=new JSONObject();for(String k:PERMS)p.put(k,DistributorUsers.allowed(u,k));a.put(new JSONObject().put("id",u.getString("id")).put("username",u.getString("username")).put("permissions",p).put("enabled",true));}return validate(a);
    }
    static JSONArray effective(JSONObject s,JSONArray local)throws Exception{
        if(revision(s)==0)return local;JSONArray all=s.getJSONObject("centralAccounts").getJSONArray("users"),active=new JSONArray();
        for(int i=0;i<all.length();i++){JSONObject u=all.getJSONObject(i);if(!u.getBoolean("enabled"))continue;JSONObject a=new JSONObject(u.toString());
            if(!a.has("hash")){JSONObject old=DistributorUsers.find(local,a.getString("id"));if(old==null)continue;a.put("salt",old.getString("salt")).put("hash",old.getString("hash"));}active.put(a);
        }return active;
    }
    static void observe(JSONObject s,String device,JSONObject request)throws Exception{
        long desired=rev(policy(s,device));if(request.optInt("accountsProtocol",0)!=1){SyncRules.require(desired==0,"Upgrade distributor first");return;}
        long reported=request.getLong("accountsRevision");JSONObject old=status(s,device);long prev=old==null?0:old.optLong("applied");
        SyncRules.require(reported>=prev&&reported<=desired&&reported>=0,"Invalid policy revision");
        JSONObject next=old==null?new JSONObject():new JSONObject(old.toString());next.put("supported",true).put("applied",reported).put("seen",System.currentTimeMillis());
        if(reported==0&&desired==0){JSONArray a=validate(request.getJSONArray("accountRoster"));for(int i=0;i<a.length();i++){a.getJSONObject(i).remove("hash");a.getJSONObject(i).remove("salt");}next.put("roster",a);}
        if(reported>0)next.remove("roster");SyncRules.map(s,"accountStatus").put(device,next);
    }
    static void activate(JSONObject s,String device)throws Exception{
        SyncRules.child(s,device);SyncRules.require(policy(s,device)==null,"Already managed");JSONObject seen=status(s,device);
        SyncRules.require(seen!=null&&seen.optBoolean("supported")&&seen.has("roster"),"Synchronize upgraded distributor first");
        JSONArray users=validate(seen.getJSONArray("roster"));SyncRules.require(users.length()>0,"No accounts");SyncRules.map(s,"accountPolicies").put(device,new JSONObject().put("revision",1L).put("users",users));
    }
    static void edit(JSONObject s,String device,String id,String name,String password,JSONObject perms,boolean enabled)throws Exception{
        JSONObject p=policy(s,device);SyncRules.require(p!=null,"Activate management");
        JSONArray all=p.getJSONArray("users");JSONObject old=id==null?null:DistributorUsers.find(all,id);
        SyncRules.require(id==null?all.length()<MAX:old!=null,"Account unavailable");name=name.trim();
        SyncRules.require(!name.isEmpty()&&name.length()<=80&&password.length()<=256&&(old!=null&&password.isEmpty()||password.length()>=6),"Invalid credentials");
        JSONObject a=old==null?new JSONObject().put("id",UUID.randomUUID().toString()):new JSONObject(old.toString());
        a.put("username",name).put("enabled",enabled).put("permissions",perms);
        if(!password.isEmpty()){String salt=UUID.randomUUID().toString();a.put("salt",salt).put("hash",DistributorUsers.hash(password,salt));}
        JSONArray next=new JSONArray();for(int i=0;i<all.length();i++){JSONObject u=all.getJSONObject(i);next.put(u.getString("id").equals(id)?a:u);}if(old==null)next.put(a);
        p.put("users",validate(next)).put("revision",Math.addExact(rev(p),1));
    }
    static boolean same(Object a,Object b)throws Exception{
        if(a instanceof JSONObject&&b instanceof JSONObject){JSONObject x=(JSONObject)a,y=(JSONObject)b;if(x.length()!=y.length())return false;for(Iterator<String> it=x.keys();it.hasNext();){String k=it.next();if(!y.has(k)||!same(x.get(k),y.get(k)))return false;}return true;}
        if(a instanceof JSONArray&&b instanceof JSONArray){JSONArray x=(JSONArray)a,y=(JSONArray)b;if(x.length()!=y.length())return false;for(int i=0;i<x.length();i++)if(!same(x.get(i),y.get(i)))return false;return true;}
        if(a instanceof Number&&b instanceof Number)return a.toString().equals(b.toString());return Objects.equals(a,b);
    }
    static void accept(JSONObject s,JSONObject response)throws Exception{
        if(!response.has("accountPolicy")){SyncRules.require(revision(s)==0,"Policy missing");return;}
        JSONObject p=response.getJSONObject("accountPolicy");long n=p.getLong("revision"),cur=revision(s);SyncRules.require(n>0&&n>=cur,"Stale policy");
        JSONObject v=new JSONObject().put("revision",n).put("users",validate(p.getJSONArray("users")));
        if(n==cur){SyncRules.require(same(s.getJSONObject("centralAccounts"),v),"Conflicting policy");return;}
        s.put("centralAccounts",v);
    }
}
