package ye.gaz.distribution;
import org.json.*;
import java.util.*;

/** Backups are encrypted by GazBackupCodec. An archived authority is not an authorization to clone it. */
final class BackupRestore {
    static final String FORMAT="gaz-snapshot-v3";
    static final class Refused extends Exception {Refused(String message){super(message);}}
    static void require(boolean condition,String reason)throws Refused{if(!condition)throw new Refused(reason);}
    static JSONObject snapshot(JSONObject local,JSONObject pairing)throws Exception{
        String role=pairing.getString("role");require("independent".equals(role)||"mother".equals(role),"لا يمكن تصدير سلطة جهاز موزع");
        return new JSONObject().put("format",FORMAT).put("createdAt",System.currentTimeMillis()).put("data",local).put("authority",pairing);
    }
    static JSONObject data(JSONObject snapshot)throws Exception{return FORMAT.equals(snapshot.optString("format"))?snapshot.getJSONObject("data"):snapshot;}
    static JSONObject authority(JSONObject snapshot)throws Exception{return FORMAT.equals(snapshot.optString("format"))?snapshot.getJSONObject("authority"):null;}
    static Map<String,JSONObject> index(JSONArray list,boolean delivery)throws Exception{
        Map<String,JSONObject> map=new LinkedHashMap<>();
        for(int i=0;i<list.length();i++){JSONObject x=list.getJSONObject(i);String key=delivery?x.getString("c")+"/"+x.getString("f"):x.getString("id");require(map.put(key,x)==null,"سجلات مكررة في النسخة");}return map;
    }
    static JSONObject prepare(JSONObject snapshot,JSONObject current,JSONObject live)throws Exception{
        JSONObject next=SyncRules.copy(data(snapshot)),archive=authority(snapshot);String role=live.getString("role");
        if("independent".equals(role)){
            require(archive==null||"independent".equals(archive.getString("role")),"هذه نسخة جوال أم مقترن؛ تحتاج إلى إجراء استرداد السلطة، ولا يجوز تشغيلها كجهاز مستقل.");
            return next;
        }
        require("mother".equals(role),"استعادة الموزع من ملف غير متاحة؛ استخدم المزامنة.");
        if(archive!=null){
            require("mother".equals(archive.getString("role")),"اختر نسخة محفوظة من الجوال الأم نفسه.");
            for(String field:new String[]{"device","pub","deployment","epoch","area"})require(live.getString(field).equals(archive.optString(field)),"النسخة لا تنتمي إلى هوية الجوال الأم الحالية؛ يلزم استرداد السلطة.");
        }
        require(live.getString("deployment").equals(next.optString("deploymentId"))&&live.getString("area").equals(next.optString("area")),"النسخة تخص حارة أو قاعدة أخرى.");
        Map<String,JSONObject> before=index(current.getJSONArray("deliveries"),true),after=index(next.getJSONArray("deliveries"),true);
        require(before.keySet().equals(after.keySet()),"النسخة وسجل التسليم الحالي مختلفان. أوقفت الاستعادة لمنع تكرار الصرف أو فقد الحركات.");
        for(String k:before.keySet())require(CentralUsers.same(before.get(k),after.get(k)),"توجد حركة تسليم مختلفة. يلزم تسوية الفروقات قبل الاستعادة.");
        Map<String,JSONObject> cycles=index(current.getJSONArray("cycles"),false),oldCycles=index(next.getJSONArray("cycles"),false);
        require(cycles.keySet().equals(oldCycles.keySet()),"تختلف الدورات؛ لا يمكن إعادة دورة محذوفة أو حذف دورة قائمة.");
        for(String id:cycles.keySet())require(cycles.get(id).optBoolean("closed")==oldCycles.get(id).optBoolean("closed"),"تختلف حالة إغلاق إحدى الدورات؛ الاستعادة موقوفة.");
        Map<String,JSONObject> families=index(current.getJSONArray("families"),false),oldFamilies=index(next.getJSONArray("families"),false);
        require(oldFamilies.keySet().containsAll(families.keySet()),"النسخة لا تشمل بعض المستفيدين الحاليين؛ لن تُحذف بياناتهم.");
        Set<String> names=new HashSet<>();for(JSONObject f:oldFamilies.values())require(names.add(DistributorUsers.normalize(f.getString("name"))),"توجد أسماء مستفيدين متكررة؛ راجع النسخة.");
        next.put("users",current.getJSONArray("users")).put("deliveries",current.getJSONArray("deliveries")).put("n",Math.max(current.getLong("n"),next.getLong("n")));
        // Never replace the live pairing, grants, receipts, outbox, policies or device identity.
        try{SyncRules.protectMother(live,current,next);}catch(Exception e){throw new Refused("النسخة تتعارض مع تخصيص قائم أو حركة تمت مزامنتها؛ لم تُغيّر البيانات.");}
        return next;
    }
}
