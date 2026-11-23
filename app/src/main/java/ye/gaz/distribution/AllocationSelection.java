package ye.gaz.distribution;

import org.json.*;
import java.util.*;

final class AllocationSelection {
    private final List<JSONObject> available=new ArrayList<>();
    private final Set<String> selected=new LinkedHashSet<>();
    private final Set<String> eligible=new HashSet<>();
    AllocationSelection(JSONObject local,JSONObject grants,String cycle)throws Exception{
        JSONObject c=SyncRules.find(local.getJSONArray("cycles"),cycle);
        SyncRules.require(c!=null&&!c.optBoolean("closed"),"Cycle closed");
        Set<String> delivered=new HashSet<>();JSONArray movements=local.getJSONArray("deliveries");
        for(int i=0;i<movements.length();i++){JSONObject d=movements.getJSONObject(i);if(cycle.equals(d.getString("c")))delivered.add(d.getString("f"));}
        JSONArray all=local.getJSONArray("families");
        for(int i=0;i<all.length();i++){JSONObject f=all.getJSONObject(i);String id=f.getString("id");
            if(!grants.has(SyncRules.key(cycle,id))&&!delivered.contains(id)){available.add(f);if(!AidQuota.needsMembers(c,f))eligible.add(id);}
        }
        available.sort(Comparator.comparingInt(f->f.optInt("no")));
    }
    static String norm(String value){
        String s=DistributorUsers.normalize(value);StringBuilder b=new StringBuilder(s.length());
        for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c>='٠'&&c<='٩')c=(char)('0'+c-'٠');else if(c>='۰'&&c<='۹')c=(char)('0'+c-'۰');b.append(c);}
        return b.toString().replaceAll("\\s+"," ").trim();
    }
    Map<String,String> squares(){
        Map<String,String> out=new TreeMap<>();for(JSONObject f:available){String v=f.optString("square","").trim();out.putIfAbsent(norm(v),v.isEmpty()?"غير محدد":v);}return out;
    }
    static String label(JSONObject f){String sq=f.optString("square","").trim();return f.optInt("no")+" · "+f.optString("name")+"\nرابط الأسرة: "+(f.optString("house").isEmpty()?"—":f.optString("house"))+" | مربع: "+(sq.isEmpty()?"غير محدد":sq);}
    List<JSONObject> visible(String query,String square){
        String q=norm(query);List<JSONObject> out=new ArrayList<>();for(JSONObject f:available){
            if(square!=null&&!square.equals(norm(f.optString("square",""))))continue;
            String hay=norm(label(f)+" "+f.optString("area",""));boolean matched=true;for(String token:q.split(" "))if(!hay.contains(token)){matched=false;break;}if(matched)out.add(f);
        }return out;
    }
    int total(){return available.size();}
    int count(){return selected.size();}
    boolean checked(String id){return selected.contains(id);}
    void set(String id,boolean on){if(!eligible.contains(id))return;if(on)selected.add(id);else selected.remove(id);}
    void setAll(List<JSONObject> rows,boolean on){for(JSONObject f:rows)set(f.optString("id"),on);}
    void clear(){selected.clear();}
    int countVisible(List<JSONObject> rows){int n=0;for(JSONObject f:rows)if(checked(f.optString("id")))n++;return n;}
    List<String> ids(){return new ArrayList<>(selected);}
}
