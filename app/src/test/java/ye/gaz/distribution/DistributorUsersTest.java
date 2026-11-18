package ye.gaz.distribution;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.util.UUID;
public class DistributorUsersTest{
    @Test public void legacyAdminAndScopedUsers()throws Exception{
        String admin=UUID.randomUUID().toString(),salt=UUID.randomUUID().toString();
        JSONArray list=new JSONArray().put(new JSONObject().put("id",admin).put("username","مدير").put("permissions",new JSONObject().put("admin",true)).put("salt",salt).put("hash",DistributorUsers.hash("كلمة123",salt)));
        assertTrue(DistributorUsers.allowed(DistributorUsers.login(list,"مدير","كلمة123"),"deliver"));
        assertNull(DistributorUsers.login(list,"مدير","wrong"));
        JSONArray added=DistributorUsers.save(list,admin,null,"موزع","password",new JSONObject().put("deliver",true));
        JSONObject child=DistributorUsers.login(added,"موزع","password");String id=child.getString("id");
        assertTrue(DistributorUsers.allowed(child,"deliver"));assertFalse(DistributorUsers.allowed(child,"sync"));assertFalse(DistributorUsers.allowed(child,"admin"));
        assertFalse(DistributorUsers.publicUser(child).has("hash"));
        assertThrows(SecurityException.class,()->DistributorUsers.save(added,id,null,"آخر","password",new JSONObject()));
        assertThrows(SecurityException.class,()->DistributorUsers.remove(added,admin,admin));
        assertThrows(SecurityException.class,()->DistributorUsers.save(added,admin,admin,"مدير","",new JSONObject()));
        JSONArray updated=DistributorUsers.save(added,admin,id,"موزع","",new JSONObject().put("sync",true));
        JSONObject changed=DistributorUsers.login(updated,"موزع","password");assertTrue(DistributorUsers.allowed(changed,"sync"));assertFalse(DistributorUsers.allowed(changed,"deliver"));
    }
}
