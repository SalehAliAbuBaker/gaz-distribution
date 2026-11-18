package ye.gaz.distribution;

import org.json.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Local distributor accounts; legacy administrator remains authorized after upgrade. */
final class DistributorUsers {
    static final String[] PERMS={"view","deliver","history","reports","share","sync","admin"};
    static final String[] LABELS={"عرض الأسر المخصصة","تسجيل التسليم","عرض سجل التسليم","عرض الكشوف والتقارير","مشاركة الكشوف","المزامنة مع الجوال الأم","إدارة المستخدمين"};
    static String normalize(String x){return x.trim().toLowerCase(Locale.ROOT).replaceAll("[\\u064B-\\u065F\\u0670\\u0640]","").replaceAll("[أإآ]","ا").replace('ة','ه').replace('ى','ي');}
    static boolean allowed(JSONObject u,String p){if(u==null)return false;JSONObject a=u.optJSONObject("permissions");if(a==null||!Arrays.asList(PERMS).contains(p))return false;return a.optBoolean("admin")||a.optBoolean(p);}
    static JSONObject find(JSONArray users,String id)throws Exception{for(int i=0;i<users.length();i++){JSONObject u=users.getJSONObject(i);if(id.equals(u.getString("id")))return u;}return null;}
    static String hash(String password,String salt)throws Exception{
        PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt.getBytes(StandardCharsets.UTF_8),120000,256);
        try{byte[] b=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();StringBuilder h=new StringBuilder();for(byte x:b)h.append(String.format(Locale.ROOT,"%02x",x&255));Arrays.fill(b,(byte)0);return h.toString();}finally{spec.clearPassword();}
    }
    static JSONObject login(JSONArray users,String username,String password)throws Exception{
        if(username.length()>120||password.length()>256)return null;String n=normalize(username);
        for(int i=0;i<users.length();i++){JSONObject u=users.getJSONObject(i);if(normalize(u.getString("username")).equals(n)){
            if(MessageDigest.isEqual(hash(password,u.getString("salt")).getBytes(StandardCharsets.US_ASCII),u.getString("hash").getBytes(StandardCharsets.US_ASCII)))return u;return null;}}
        return null;
    }
    static JSONObject publicUser(JSONObject user)throws Exception{
        JSONObject perms=new JSONObject();for(String p:PERMS)perms.put(p,allowed(user,p));
        return new JSONObject().put("id",user.getString("id")).put("username",user.getString("username")).put("permissions",perms);
    }
    static JSONArray save(JSONArray users,String actor,String id,String name,String password,JSONObject perms)throws Exception{
        if(!allowed(find(users,actor),"admin"))throw new SecurityException("Admin required");
        name=name.trim();if(name.isEmpty()||name.length()>80||password.length()>256)throw new IllegalArgumentException("Invalid name/password");
        JSONObject old=id==null?null:find(users,id);if(id!=null&&old==null)throw new IllegalArgumentException("Unknown account");
        for(int i=0;i<users.length();i++){JSONObject u=users.getJSONObject(i);if(!u.optString("id").equals(id)&&normalize(u.getString("username")).equals(normalize(name)))throw new IllegalArgumentException("Duplicate name");}
        if((old==null||!password.isEmpty())&&password.length()<6)throw new IllegalArgumentException("Password too short");
        JSONObject p=new JSONObject();for(String k:PERMS)p.put(k,perms.optBoolean(k));if(p.optBoolean("share"))p.put("reports",true);
        if(actor.equals(id)&&!p.optBoolean("admin"))throw new SecurityException("Cannot remove current admin");
        JSONObject u=new JSONObject().put("id",old==null?UUID.randomUUID().toString():id).put("username",name).put("permissions",p);
        if(password.isEmpty()){u.put("salt",old.getString("salt")).put("hash",old.getString("hash"));}
        else{String salt=UUID.randomUUID().toString();u.put("salt",salt).put("hash",hash(password,salt));}
        JSONArray next=new JSONArray();for(int i=0;i<users.length();i++){JSONObject a=users.getJSONObject(i);next.put(a.getString("id").equals(id)?u:a);}if(old==null)next.put(u);return next;
    }
    static JSONArray remove(JSONArray users,String actor,String id)throws Exception{
        if(!allowed(find(users,actor),"admin")||actor.equals(id)||find(users,id)==null)throw new SecurityException("Cannot delete account");
        JSONArray next=new JSONArray();for(int i=0;i<users.length();i++){JSONObject u=users.getJSONObject(i);if(!id.equals(u.getString("id")))next.put(u);}return next;
    }
}
