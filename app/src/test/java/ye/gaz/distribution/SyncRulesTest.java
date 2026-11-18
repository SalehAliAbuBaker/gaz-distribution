package ye.gaz.distribution;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.UUID;
public class SyncRulesTest {
    static String id(){return UUID.randomUUID().toString();}
    static KeyPair key()throws Exception{KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec("secp256r1"));return g.generateKeyPair();}
    static String pub(KeyPair k){return PairPackets.b64(k.getPublic().getEncoded());}
    static class Fixture{
        KeyPair m=key(),a=key(),b=key();String dep=id(),epoch=id(),mother=id(),one=id(),two=id(),cycle=id(),family=id(),other=id(),k=SyncRules.key(cycle,family);
        JSONObject ms=new JSONObject().put("role","mother").put("device",mother).put("deployment",dep).put("epoch",epoch).put("pub",pub(m)).put("devices",new JSONObject().put(one,new JSONObject().put("pub",pub(a)).put("name","A")).put(two,new JSONObject().put("pub",pub(b)).put("name","B")));
        JSONObject as=child(one),bs=child(two);
        JSONObject local=new JSONObject().put("cycles",new JSONArray().put(new JSONObject().put("id",cycle).put("title","دورة").put("date","2026-11-18").put("quota",2).put("closed",false))).put("families",new JSONArray().put(f(family,1)).put(f(other,2))).put("deliveries",new JSONArray());
        JSONObject f(String id,int no)throws Exception{return new JSONObject().put("id",id).put("name","أسرة").put("house","1").put("no",no);}
        JSONObject child(String id)throws Exception{return new JSONObject().put("role","distributor").put("device",id).put("mother",mother).put("motherPub",pub(m)).put("deployment",dep).put("epoch",epoch);}
        Fixture()throws Exception{}
        String grant()throws Exception{return SyncRules.grant(ms,local,one,cycle,family,m.getPrivate());}
        void sync()throws Exception{String challenge=id();String req=SyncWire.sign(SyncRules.request(as,challenge,a.getPrivate()),a.getPrivate());JSONObject response=SyncRules.respond(ms,local,req,challenge);SyncRules.acceptResponse(as,SyncWire.sign(response,m.getPrivate()),challenge);}
    }
    @Test public void exclusiveOfflineAndReplay()throws Exception{
        Fixture f=new Fixture();String packet=f.grant();assertThrows(Exception.class,()->SyncRules.grant(f.ms,f.local,f.two,f.cycle,f.family,f.m.getPrivate()));
        assertThrows(Exception.class,()->SyncRules.acceptGrant(f.bs,packet));f.sync();
        assertThrows(Exception.class,()->SyncRules.record(f.as,f.k,3,f.a.getPrivate()));
        SyncRules.record(f.as,f.k,2,f.a.getPrivate());assertThrows(Exception.class,()->SyncRules.record(f.as,f.k,1,f.a.getPrivate()));
        String old=SyncRules.map(f.as,"outbox").getString(f.k);f.sync();assertTrue(SyncRules.map(f.as,"acked").getBoolean(f.k));assertEquals(1,f.local.getJSONArray("deliveries").length());
        SyncRules.importReceipt(f.ms,f.local,f.one,old);f.sync();assertEquals(1,f.local.getJSONArray("deliveries").length());
    }
    @Test public void webCannotBypassReservationOrClose()throws Exception{
        Fixture f=new Fixture();f.grant();JSONObject next=SyncRules.copy(f.local);
        next.getJSONArray("deliveries").put(new JSONObject().put("c",f.cycle).put("f",f.family).put("q",1));
        assertThrows(Exception.class,()->SyncRules.protectMother(f.ms,f.local,next));
        JSONObject closed=SyncRules.copy(f.local);closed.getJSONArray("cycles").getJSONObject(0).put("closed",true);
        assertThrows(Exception.class,()->SyncRules.protectMother(f.ms,f.local,closed));
        JSONObject deleted=SyncRules.copy(f.local);deleted.getJSONArray("families").remove(0);assertThrows(Exception.class,()->SyncRules.protectMother(f.ms,f.local,deleted));
    }
    @Test public void authenticatedResponseAndChallenge()throws Exception{
        Fixture f=new Fixture();f.grant();String challenge=id();JSONObject req=SyncRules.request(f.as,challenge,f.a.getPrivate());
        assertThrows(Exception.class,()->SyncRules.respond(f.ms,f.local,SyncWire.sign(req,f.b.getPrivate()),challenge));
        String packet=SyncWire.sign(req,f.a.getPrivate());assertThrows(Exception.class,()->SyncRules.respond(f.ms,f.local,packet,id()));
        JSONObject response=SyncRules.respond(f.ms,f.local,packet,challenge);
        assertThrows(Exception.class,()->SyncRules.acceptResponse(f.as,SyncWire.sign(response,f.b.getPrivate()),challenge));
        assertThrows(Exception.class,()->SyncRules.acceptResponse(f.as,SyncWire.sign(response,f.m.getPrivate()),id()));
    }
    @Test public void syncedDeliveryAndStaleWebSnapshotProtected()throws Exception{
        Fixture f=new Fixture();f.grant();JSONObject old=SyncRules.copy(f.local);f.sync();SyncRules.record(f.as,f.k,1,f.a.getPrivate());f.sync();
        assertThrows(Exception.class,()->SyncRules.protectMother(f.ms,f.local,old));JSONObject next=SyncRules.copy(f.local);next.getJSONArray("cycles").getJSONObject(0).put("closed",true);SyncRules.protectMother(f.ms,f.local,next);
    }
}
