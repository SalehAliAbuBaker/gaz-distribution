package ye.gaz.distribution;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;

final class GazStore extends SQLiteOpenHelper {
    private static final String KEY = "gaz-record-encryption-v1";
    static final int MAX = 64 * 1024 * 1024;
    private static final String[] KINDS={"families","cycles","deliveries","users"};
    private final SecretKey secret;
    // In-process only: these snapshots are never written outside encrypted SQLite.
    private Map<String,String> decryptedRowsCache;
    private String stateCache;
    private void clearReadCache(){decryptedRowsCache=null;stateCache=null;}
    @Override public synchronized void close(){clearReadCache();super.close();}
    GazStore(Context c) throws Exception {
        super(c,"gaz-encrypted-records.db",null,2);
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias(KEY)){
            if(c.getDatabasePath("gaz-encrypted-records.db").exists())throw new IllegalStateException("Encryption key missing");
            KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(KEY,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
            gen.generateKey();
        }
        secret=(SecretKey)ks.getKey(KEY,null);
        if(secret==null)throw new IllegalStateException("Encryption key unavailable");
    }
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE records(kind TEXT NOT NULL, rid TEXT NOT NULL, encrypted BLOB NOT NULL, modified INTEGER NOT NULL, PRIMARY KEY(kind,rid))");
        createPairTable(db);
    }
    private static void createPairTable(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS pairing(singleton INTEGER PRIMARY KEY CHECK(singleton=1), encrypted BLOB NOT NULL)");}
    @Override public void onUpgrade(SQLiteDatabase db,int old,int now){if(old==1&&now==2){createPairTable(db);return;}throw new IllegalStateException("Migration required");}
    synchronized JSONObject pairRead()throws Exception{
        try(Cursor c=getReadableDatabase().rawQuery("SELECT encrypted FROM pairing WHERE singleton=1",null)){
            if(c.moveToFirst())return new JSONObject(open("gaz-pairing/v1",c.getBlob(0)));
            return new JSONObject().put("role","independent");
        }
    }
    synchronized String role()throws Exception{return pairRead().getString("role");}
    synchronized void pairWrite(JSONObject state)throws Exception{
        String old=role(),next=state.getString("role");
        boolean allowed=old.equals(next)||(old.equals("independent")&&(next.equals("mother")||next.equals("pending")))||(old.equals("pending")&&(next.equals("independent")||next.equals("distributor")));
        if(!allowed)throw new IllegalStateException("Invalid pair transition");
        if(old.equals("independent")&&next.equals("pending")){JSONObject local=new JSONObject(readState());for(String k:new String[]{"families","cycles","deliveries"})if(local.getJSONArray(k).length()!=0)throw new IllegalStateException("Nonempty distributor");}
        if(old.equals("independent")&&next.equals("mother")){JSONObject local=new JSONObject(readState());if(!local.getString("deploymentId").equals(state.getString("deployment"))||!local.getString("area").equals(state.getString("area")))throw new IllegalStateException("Local state changed");}
        ContentValues v=new ContentValues();v.put("singleton",1);v.put("encrypted",seal("gaz-pairing/v1",state.toString()));
        if(getWritableDatabase().insertWithOnConflict("pairing",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("Pair write failed");
    }
    synchronized void writeLocalState(String json)throws Exception{
        JSONObject state=pairRead();String role=state.getString("role");
        if(role.equals("pending")||role.equals("distributor"))throw new IllegalStateException("Distributor data is not provisioned");
        if(role.equals("mother")){JSONObject next=new JSONObject(json);if(!state.getString("deployment").equals(next.getString("deploymentId"))||!state.getString("area").equals(next.getString("area")))throw new IllegalStateException("Mother neighborhood locked");SyncRules.protectMother(state,new JSONObject(readState()),next);BagInventory.verify(next,state);}
        writeState(json);
    }
    synchronized void commitSync(JSONObject paired,JSONObject local)throws Exception{
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            if("mother".equals(paired.optString("role",""))){BagInventory.verify(local==null?new JSONObject(readState()):local,paired);}
            if(local!=null)writeState(local.toString());
            pairWrite(paired);db.setTransactionSuccessful();
        }finally{try{db.endTransaction();}finally{clearReadCache();}}
    }
    synchronized void restoreIndependent(String json)throws Exception{if(!role().equals("independent"))throw new IllegalStateException("Pair recovery not implemented");writeState(json);}
    synchronized JSONArray loginAccounts()throws Exception{
        if("distributor".equals(role()))return distributorUsers();String raw=readState();return "null".equals(raw)?new JSONArray():new JSONObject(raw).getJSONArray("users");
    }
    synchronized JSONObject loginAccount(String id)throws Exception{return DistributorUsers.find(loginAccounts(),id);}
    synchronized JSONObject importPreview(String admin,JSONArray rows)throws Exception{
        String role=role();if(!("mother".equals(role)||"independent".equals(role))||!admin(admin))throw new SecurityException("Admin required");
        return BeneficiaryExcel.preview(new JSONObject(readState()),rows);
    }
    synchronized int importBeneficiaries(String admin,JSONArray rows)throws Exception{
        String role=role();if(!("mother".equals(role)||"independent".equals(role))||!admin(admin))throw new SecurityException("Admin required");
        JSONObject local=new JSONObject(readState());int count=BeneficiaryExcel.append(local,rows);writeLocalState(local.toString());return count;
    }
    synchronized JSONArray distributorUsers()throws Exception{
        if(!"distributor".equals(role()))throw new SecurityException("Distributor required");
        return CentralUsers.effective(pairRead(),new JSONObject(readState()).getJSONArray("users"));
    }
    synchronized void saveDistributorUser(String actor,String id,String name,String password,JSONObject perms)throws Exception{
        if(CentralUsers.revision(pairRead())>0)throw new SecurityException("Managed centrally");JSONArray next=DistributorUsers.save(distributorUsers(),actor,id,name,password,perms);
        JSONObject local=new JSONObject(readState());local.put("users",next);writeState(local.toString());
    }
    synchronized void removeDistributorUser(String actor,String id)throws Exception{
        if(CentralUsers.revision(pairRead())>0)throw new SecurityException("Managed centrally");JSONArray next=DistributorUsers.remove(distributorUsers(),actor,id);
        JSONObject local=new JSONObject(readState());local.put("users",next);writeState(local.toString());
    }
    synchronized String distributorWebState()throws Exception{
        JSONObject local=new JSONObject(readState());JSONArray safe=new JSONArray();
        if(local.getJSONArray("users").length()>0)safe.put(new JSONObject().put("id","local").put("username","").put("permissions",new JSONObject()));
        local.put("users",safe);return local.toString();
    }
    synchronized boolean admin(String id)throws Exception{
        String raw=readState();if("null".equals(raw))return false;JSONArray users=new JSONObject(raw).getJSONArray("users");
        for(int i=0;i<users.length();i++){JSONObject u=users.getJSONObject(i);if(id.equals(u.getString("id")))return u.getJSONObject("permissions").optBoolean("admin");}return false;
    }
    private static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private byte[] seal(String key,String value) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,secret);c.updateAAD(utf(key));byte[] iv=c.getIV(),body=c.doFinal(utf(value));
        return ByteBuffer.allocate(iv.length+body.length).put(iv).put(body).array();
    }
    private String open(String key,byte[] blob) throws Exception {
        if(blob.length<28)throw new IllegalArgumentException("Truncated row");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,secret,new GCMParameterSpec(128,blob,0,12));c.updateAAD(utf(key));
        return new String(c.doFinal(blob,12,blob.length-12),StandardCharsets.UTF_8);
    }
    private static String key(String kind,String id){return kind+"\u0000"+id;}
    private Map<String,String> readRows(SQLiteDatabase db) throws Exception {
        if(decryptedRowsCache!=null)return new LinkedHashMap<>(decryptedRowsCache);
        Map<String,String> rows=new LinkedHashMap<>();
        try(Cursor c=db.rawQuery("SELECT kind,rid,encrypted FROM records ORDER BY kind,rid",null)){
            while(c.moveToNext()){String k=key(c.getString(0),c.getString(1));rows.put(k,open(k,c.getBlob(2)));}
        }
        decryptedRowsCache=new LinkedHashMap<>(rows);
        return rows;
    }
    synchronized String readState() throws Exception {
        if(stateCache!=null)return stateCache;
        Map<String,String> rows=readRows(getReadableDatabase()); if(rows.isEmpty())return "null";
        String meta=rows.remove(key("meta","state"));if(meta==null)throw new IllegalStateException("Metadata missing");
        JSONObject state=new JSONObject(meta);JSONObject order=state.optJSONObject("__gazOrder");state.remove("__gazOrder");for(String kind:KINDS){JSONArray a=new JSONArray();JSONArray ids=order==null?null:order.optJSONArray(kind);if(ids!=null){for(int i=0;i<ids.length();i++){String row=rows.remove(key(kind,ids.getString(i)));if(row==null)throw new IllegalStateException("Record ordering invalid");a.put(new JSONObject(row));}}else{for(Map.Entry<String,String> e:rows.entrySet())if(e.getKey().startsWith(kind+"\u0000"))a.put(new JSONObject(e.getValue()));}state.put(kind,a);}if(order!=null&&!rows.isEmpty())throw new IllegalStateException("Extra records");
        flatten(state);stateCache=state.toString();return stateCache;
    }
    private static String id(JSONObject x,String k) throws JSONException {
        String s=x.getString(k);if(s.isEmpty()||s.length()>150||s.indexOf('\u0000')>=0||s.indexOf('/')>=0)throw new JSONException("Invalid identifier");return s;
    }
    /** A blank household size is explicitly unknown, not zero. Kept distinct from invalid values. */
    static boolean validFamilyMembers(JSONObject f){
        if(!f.has("members"))return false;
        Object raw=f.opt("members");
        if(raw==JSONObject.NULL)return true;
        if(raw instanceof String){
            String value=((String)raw).trim();
            if(!value.matches("[0-9]{1,4}"))return false;
            int n=Integer.parseInt(value);
            return n>=1&&n<=1000;
        }
        if(!(raw instanceof Number))return false;
        double value=((Number)raw).doubleValue();
        return Double.isFinite(value)&&value>=1&&value<=1000&&value==Math.rint(value);
    }
    static Map<String,String> flatten(JSONObject state) throws Exception {
        if(state.getString("area").trim().isEmpty()||state.getLong("n")<0)throw new JSONException("Invalid area");
        if(state.has("deploymentId"))id(state,"deploymentId");
        RafdIdentityNumber.validateUnique(state.getJSONArray("families"));
        Map<String,String> rows=new LinkedHashMap<>();Set<String> family=new HashSet<>(),cycles=new HashSet<>(),numbers=new HashSet<>();
        for(String kind:KINDS){
            JSONArray list=state.getJSONArray(kind);
            for(int i=0;i<list.length();i++){
                JSONObject x=list.getJSONObject(i);String rid;
                if(kind.equals("deliveries")){
                    String c=id(x,"c"),f=id(x,"f");if(!cycles.contains(c)||!family.contains(f)||x.getLong("q")<=0)throw new JSONException("Invalid delivery");RafdMoney.checkDelivery(x);rid=c+"/"+f;
                }else{
                    rid=id(x,"id");
                    if(kind.equals("cycles"))RafdMoney.checkCycle(x);
                    if(kind.equals("families")){
                        if(x.has("archived")&&!(x.get("archived") instanceof Boolean))throw new JSONException("Invalid archive flag");
                        if(x.has("square")&&(!(x.get("square") instanceof String)||x.getString("square").length()>80))throw new JSONException("Invalid square");
                        if(!family.add(rid)||x.getLong("no")<1||x.getLong("no")>state.getLong("n")||!numbers.add(""+x.getLong("no"))||!validFamilyMembers(x)||x.getString("name").trim().isEmpty()||!(x.opt("house") instanceof String)||x.getString("house").length()>200)throw new JSONException("Invalid beneficiary");
                    }else if(kind.equals("cycles")){cycles.add(rid);if(x.getString("title").trim().isEmpty()||x.getInt("quota")<1)throw new JSONException("Invalid cycle");AidQuota.validate(x);}
                    else {x.getJSONObject("permissions");x.getString("hash");x.getString("salt");}
                }
                if(rows.put(key(kind,rid),x.toString())!=null)throw new JSONException("Duplicate record");
            }
        }
        BagInventory.verify(state,null);
        JSONObject meta=new JSONObject(state.toString());JSONObject order=new JSONObject();for(String kind:KINDS){JSONArray ids=new JSONArray();JSONArray list=state.getJSONArray(kind);for(int i=0;i<list.length();i++){JSONObject x=list.getJSONObject(i);ids.put(kind.equals("deliveries")?id(x,"c")+"/"+id(x,"f"):id(x,"id"));}order.put(kind,ids);meta.remove(kind);}meta.put("__gazOrder",order);
        rows.put(key("meta","state"),meta.toString());return rows;
    }
    synchronized void writeState(String json) throws Exception {
        if(json.length()>MAX)throw new IllegalArgumentException("State too large for test build");
        Map<String,String> next=flatten(new JSONObject(json));SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        boolean committed=false;
        try{
            Map<String,String> prev=readRows(db);
            for(String k:prev.keySet())if(!next.containsKey(k)){int p=k.indexOf('\u0000');db.delete("records","kind=? AND rid=?",new String[]{k.substring(0,p),k.substring(p+1)});}
            for(Map.Entry<String,String> e:next.entrySet()){
                String k=e.getKey();if(Objects.equals(prev.get(k),e.getValue()))continue;
                int p=k.indexOf('\u0000');ContentValues v=new ContentValues();v.put("kind",k.substring(0,p));v.put("rid",k.substring(p+1));v.put("encrypted",seal(k,e.getValue()));v.put("modified",System.currentTimeMillis());
                if(db.insertWithOnConflict("records",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("Write failed");
            }db.setTransactionSuccessful();committed=true;
        }finally{
            boolean ended=false;
            try{db.endTransaction();ended=true;}finally{
                // Never trust a cache after a failed commit, including outer sync rollbacks.
                if(committed&&ended){decryptedRowsCache=new LinkedHashMap<>(next);stateCache=json;}
                else clearReadCache();
            }
        }
    }
    synchronized byte[] backup(char[] pass) throws Exception {
        String contents=readState();if("null".equals(contents))throw new IllegalStateException("No data");
        JSONObject snapshot=BackupRestore.snapshot(new JSONObject(contents),pairRead());
        return GazBackupCodec.encode(snapshot.toString(),pass);
    }
    static String decodeBackup(byte[] bytes,char[] pass) throws Exception {
        String json=GazBackupCodec.decode(bytes,pass);JSONObject snapshot=new JSONObject(json);flatten(BackupRestore.data(snapshot));BackupRestore.authority(snapshot);return json;
    }
    synchronized JSONObject previewRestore(String snapshot)throws Exception{
        String raw=readState();JSONObject current="null".equals(raw)?new JSONObject():new JSONObject(raw);
        JSONObject next=BackupRestore.prepare(new JSONObject(snapshot),current,pairRead());flatten(next);return next;
    }
    synchronized void restoreSafe(String snapshot)throws Exception{
        JSONObject next=previewRestore(snapshot);
        writeState(next.toString());
    }
}
