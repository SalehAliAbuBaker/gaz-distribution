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
    @Test public void squareUpdatesOnExistingGrantWithoutChangingReceipt()throws Exception{
        Fixture f=new Fixture();f.grant();f.sync();assertEquals("",SyncRules.map(f.as,"squareLabels").getString(f.k));
        SyncRules.record(f.as,f.k,1,f.a.getPrivate());f.sync();
        String packet=SyncRules.map(f.as,"grants").getString(f.k),receipt=SyncRules.map(f.as,"outbox").getString(f.k);
        f.local.getJSONArray("families").getJSONObject(0).put("square","المربع ٣");f.sync();
        assertEquals("المربع ٣",SyncRules.map(f.as,"squareLabels").getString(f.k));assertEquals(packet,SyncRules.map(f.as,"grants").getString(f.k));assertEquals(receipt,SyncRules.map(f.as,"outbox").getString(f.k));assertEquals(1,f.local.getJSONArray("deliveries").length());
    }
    @Test public void motherCorrectsAndDeletesWithoutReplay()throws Exception{
        Fixture f=new Fixture();f.grant();f.sync();SyncRules.record(f.as,f.k,1,f.a.getPrivate());f.sync();
        String original=SyncRules.map(f.as,"outbox").getString(f.k);
        MotherDelivery.change(f.ms,f.local,f.k,"edit",2,"مدير","تصحيح");f.sync();assertEquals(2,MotherDelivery.control(f.as,f.k).getJSONObject("delivery").getInt("q"));
        MotherDelivery.change(f.ms,f.local,f.k,"delete",0,"مدير","إلغاء");SyncRules.importReceipt(f.ms,f.local,f.one,original);f.sync();assertNull(SyncRules.delivery(f.local,f.cycle,f.family));assertTrue(MotherDelivery.control(f.as,f.k).isNull("delivery"));
        assertThrows(Exception.class,()->SyncRules.record(f.as,f.k,1,f.a.getPrivate()));
        MotherDelivery.change(f.ms,f.local,f.k,"add",1,"مدير","إثبات");f.sync();assertEquals(1,SyncRules.delivery(f.local,f.cycle,f.family).getInt("q"));
        assertEquals(original,SyncRules.map(f.ms,"receipts").getString(f.k));
    }
    @Test public void recallNeedsDurableChildLock()throws Exception{
        Fixture f=new Fixture();f.grant();f.sync();MotherDelivery.change(f.ms,f.local,f.k,"recall",0,"مدير","استرداد");
        assertThrows(Exception.class,()->MotherDelivery.change(f.ms,f.local,f.k,"add",1,"مدير","تسليم"));f.sync();
        assertThrows(Exception.class,()->SyncRules.record(f.as,f.k,1,f.a.getPrivate()));assertFalse(MotherDelivery.resolved(f.ms,f.k));f.sync();assertTrue(MotherDelivery.resolved(f.ms,f.k));
        MotherDelivery.change(f.ms,f.local,f.k,"add",1,"مدير","تسليم");f.sync();assertEquals(1,MotherDelivery.control(f.as,f.k).getJSONObject("delivery").getInt("q"));
    }
    @Test public void pendingReceiptDuringRecallIsNotLost()throws Exception{
        Fixture f=new Fixture();f.grant();f.sync();MotherDelivery.change(f.ms,f.local,f.k,"recall",0,"مدير","استرداد");SyncRules.record(f.as,f.k,1,f.a.getPrivate());
        f.sync();assertEquals(1,SyncRules.delivery(f.local,f.cycle,f.family).getInt("q"));f.sync();assertTrue(MotherDelivery.resolved(f.ms,f.k));
        MotherDelivery.change(f.ms,f.local,f.k,"edit",2,"مدير","تصحيح");f.sync();assertEquals(2,MotherDelivery.control(f.as,f.k).getJSONObject("delivery").getInt("q"));
    }
    @Test public void centralBatchKeepsSignedReceipt()throws Exception{
        Fixture f=new Fixture();f.grant();f.sync();SyncRules.record(f.as,f.k,1,f.a.getPrivate());f.sync();
        assertEquals(1,MotherDelivery.batch(f.ms,f.local,f.cycle,"delete",new JSONObject(),"مدير","إلغاء الكشف"));
        assertEquals(2,MotherDelivery.batch(f.ms,f.local,f.cycle,"add",new JSONObject().put(f.family,2).put(f.other,1),"مدير","تسليم جماعي"));f.sync();
        assertEquals(2,f.local.getJSONArray("deliveries").length());assertEquals(2,MotherDelivery.control(f.as,f.k).getJSONObject("delivery").getInt("q"));
    }
    @Test public void familyLinkChangeOnAllocatedFamilyPreservesReceipt()throws Exception{
        Fixture f=new Fixture();String grant=f.grant();f.sync();SyncRules.record(f.as,f.k,1,f.a.getPrivate());f.sync();
        String receipt=SyncRules.map(f.ms,"receipts").getString(f.k);JSONObject next=SyncRules.copy(f.local);next.getJSONArray("families").getJSONObject(0).put("house","أسرة آل سالم");
        SyncRules.protectMother(f.ms,f.local,next);f.local=next;f.sync();assertEquals("أسرة آل سالم",SyncRules.map(f.as,"houseLabels").getString(f.k));
        next=SyncRules.copy(f.local);next.getJSONArray("families").getJSONObject(0).put("house","");SyncRules.protectMother(f.ms,f.local,next);f.local=next;f.sync();
        assertEquals("",SyncRules.map(f.as,"houseLabels").getString(f.k));assertEquals(grant,SyncRules.map(f.as,"grants").getString(f.k));assertEquals(receipt,SyncRules.map(f.ms,"receipts").getString(f.k));assertEquals(1,f.local.getJSONArray("deliveries").length());
    }
    @Test public void familyTierSignedGrantAndFrozenRules()throws Exception{
        Fixture f=new Fixture();JSONObject c=f.local.getJSONArray("cycles").getJSONObject(0);
        c.put("quota",1).put("aidTwoAbove","5").put("aidThreeAbove","9");f.local.getJSONArray("families").getJSONObject(0).put("members",10);f.local.getJSONArray("families").getJSONObject(1).put("members",6);
        assertEquals(3,SyncWire.peek(f.grant()).getInt("maxQty"));
        assertEquals(2,SyncWire.peek(SyncRules.grant(f.ms,f.local,f.two,f.cycle,f.other,f.m.getPrivate())).getInt("maxQty"));
        JSONObject changed=SyncRules.copy(f.local);changed.getJSONArray("cycles").getJSONObject(0).put("aidThreeAbove","10");
        assertThrows(Exception.class,()->SyncRules.protectMother(f.ms,f.local,changed));
        f.sync();assertThrows(Exception.class,()->SyncRules.record(f.as,f.k,4,f.a.getPrivate()));SyncRules.record(f.as,f.k,3,f.a.getPrivate());f.sync();assertEquals(3,f.local.getJSONArray("deliveries").getJSONObject(0).getInt("q"));
    }
    @Test public void motherCanRenameAndArchiveLinkedFamilyWhilePreservingReceipt()throws Exception {
        Fixture f=new Fixture();String grant=f.grant();f.sync();
        SyncRules.record(f.as,f.k,1,f.a.getPrivate());f.sync();
        String receipt=SyncRules.map(f.ms,"receipts").getString(f.k);
        JSONObject changed=SyncRules.copy(f.local);
        JSONObject person=changed.getJSONArray("families").getJSONObject(0);
        person.put("name","اسم مصحح").put("identityNo","0012345678").put("archived",true);
        SyncRules.protectMother(f.ms,f.local,changed);
        f.local=changed;f.sync();
        assertEquals("اسم مصحح",SyncRules.map(f.as,"nameLabels").getString(f.k));
        assertEquals(grant,SyncRules.map(f.as,"grants").getString(f.k));
        assertEquals(receipt,SyncRules.map(f.ms,"receipts").getString(f.k));
        assertEquals(1,f.local.getJSONArray("deliveries").length());
        JSONObject renumbered=SyncRules.copy(f.local);
        renumbered.getJSONArray("families").getJSONObject(0).put("no",999);
        assertThrows(Exception.class,()->SyncRules.protectMother(f.ms,f.local,renumbered));
        JSONObject restored=SyncRules.copy(f.local);
        restored.getJSONArray("families").getJSONObject(0).put("archived",false);
        SyncRules.protectMother(f.ms,f.local,restored);
    }
    @Test public void archivedRegistrantCannotReceiveFreshGrant()throws Exception {
        Fixture f=new Fixture();
        f.local.getJSONArray("families").getJSONObject(0).put("archived",true);
        assertThrows(Exception.class,()->f.grant());
    }

}
