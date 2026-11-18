package ye.gaz.distribution;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.UUID;
public class CentralUsersTest {
    @Test public void migrateWithoutExportingSecretsAndSuspend()throws Exception{
        String device=UUID.randomUUID().toString(),id=UUID.randomUUID().toString(),salt=UUID.randomUUID().toString();
        JSONArray local=new JSONArray().put(new JSONObject().put("id",id).put("username","مدير").put("permissions",new JSONObject().put("admin",true)).put("salt",salt).put("hash",DistributorUsers.hash("secret123",salt)));
        JSONArray roster=CentralUsers.roster(local);assertFalse(roster.getJSONObject(0).has("hash"));
        JSONObject mother=new JSONObject().put("devices",new JSONObject().put(device,new JSONObject())),child=new JSONObject();
        CentralUsers.observe(mother,device,new JSONObject().put("accountsProtocol",1).put("accountsRevision",0).put("accountRoster",roster));
        CentralUsers.activate(mother,device);CentralUsers.accept(child,new JSONObject().put("accountPolicy",CentralUsers.policy(mother,device)));
        JSONObject user=DistributorUsers.login(CentralUsers.effective(child,local),"مدير","secret123");assertNotNull(user);assertFalse(DistributorUsers.allowed(user,"admin"));assertTrue(DistributorUsers.allowed(user,"deliver"));
        JSONObject prior=SyncRules.copy(CentralUsers.policy(mother,device));
        CentralUsers.edit(mother,device,id,"مدير","",new JSONObject().put("deliver",true),false);
        CentralUsers.accept(child,new JSONObject().put("accountPolicy",CentralUsers.policy(mother,device)));assertEquals(0,CentralUsers.effective(child,local).length());
        assertThrows(Exception.class,()->CentralUsers.accept(child,new JSONObject().put("accountPolicy",prior)));
        CentralUsers.edit(mother,device,null,"موزع","password",new JSONObject().put("view",true),true);
        CentralUsers.accept(child,new JSONObject().put("accountPolicy",CentralUsers.policy(mother,device)));
        user=DistributorUsers.login(CentralUsers.effective(child,local),"موزع","password");assertNotNull(user);assertFalse(DistributorUsers.allowed(user,"deliver"));
    }
}
