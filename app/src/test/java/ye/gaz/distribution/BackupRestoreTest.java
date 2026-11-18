package ye.gaz.distribution;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class BackupRestoreTest{
    static JSONObject authority()throws Exception{return new JSONObject().put("role","mother").put("deployment","D").put("area","الروضة").put("device","A").put("pub","K").put("epoch","E");}
    static JSONObject data()throws Exception{return new JSONObject().put("deploymentId","D").put("area","الروضة").put("n",1).put("families",new JSONArray().put(new JSONObject().put("id","f").put("name","أسرة"))).put("cycles",new JSONArray().put(new JSONObject().put("id","c").put("closed",false))).put("deliveries",new JSONArray().put(new JSONObject().put("c","c").put("f","f").put("q",1))).put("users",new JSONArray());}
    @Test public void keepsAuthorityAndUsers()throws Exception{
        JSONObject live=authority(),current=data(),old=data();current.put("n",3).put("users",new JSONArray().put(new JSONObject().put("id","now")));
        old.getJSONArray("families").getJSONObject(0).put("square","2");
        JSONObject next=BackupRestore.prepare(BackupRestore.snapshot(old,live),current,live);
        assertEquals("2",next.getJSONArray("families").getJSONObject(0).getString("square"));
        assertEquals("now",next.getJSONArray("users").getJSONObject(0).getString("id"));assertEquals(3,next.getInt("n"));assertEquals(1,next.getJSONArray("deliveries").length());
    }
    @Test public void cannotRollbackDeliveryOrCloneMother()throws Exception{
        JSONObject live=authority(),current=data(),old=data();old.put("deliveries",new JSONArray());
        assertThrows(BackupRestore.Refused.class,()->BackupRestore.prepare(BackupRestore.snapshot(old,live),current,live));
        JSONObject other=authority().put("device","B");
        assertThrows(BackupRestore.Refused.class,()->BackupRestore.prepare(BackupRestore.snapshot(current,other),current,live));
        assertThrows(BackupRestore.Refused.class,()->BackupRestore.prepare(BackupRestore.snapshot(current,live),current,new JSONObject().put("role","independent")));
        final JSONObject closed=data();closed.getJSONArray("cycles").getJSONObject(0).put("closed",true);
        assertThrows(BackupRestore.Refused.class,()->BackupRestore.prepare(closed,current,live));
    }
    @Test public void legacyIndependentRemainsReadable()throws Exception{
        JSONObject old=data();assertEquals("D",BackupRestore.prepare(old,new JSONObject(),new JSONObject().put("role","independent")).getString("deploymentId"));
    }
}
