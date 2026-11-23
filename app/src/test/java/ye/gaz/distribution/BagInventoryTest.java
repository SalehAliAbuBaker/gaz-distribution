package ye.gaz.distribution;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Total bags = delivered plus outstanding offline grants, never double-counted. */
public class BagInventoryTest {
    @Test public void reservesBeforeOfflineDeliveryThenReleasesUnusedDifference()throws Exception{
        SyncRulesTest.Fixture f=new SyncRulesTest.Fixture();
        JSONObject cycle=f.local.getJSONArray("cycles").getJSONObject(0)
            .put("stockTotal",3).put("stockWarningAt",1);
        BagInventory.Balance b=BagInventory.balance(f.local,f.ms,f.cycle);
        assertEquals(Integer.valueOf(3),b.total);assertEquals(3,b.available);
        f.grant();b=BagInventory.balance(f.local,f.ms,f.cycle);
        assertEquals(2,b.reserved);assertEquals(1,b.available);
        assertThrows(Exception.class,()->SyncRules.grant(f.ms,f.local,f.two,f.cycle,f.other,f.m.getPrivate()));
        f.sync();SyncRules.record(f.as,f.k,1,f.a.getPrivate());f.sync();
        b=BagInventory.balance(f.local,f.ms,f.cycle);
        assertEquals(1,b.delivered);assertEquals(0,b.reserved);assertEquals(2,b.available);
        SyncRules.grant(f.ms,f.local,f.two,f.cycle,f.other,f.m.getPrivate());
        b=BagInventory.balance(f.local,f.ms,f.cycle);
        assertEquals(1,b.delivered);assertEquals(2,b.reserved);assertEquals(0,b.available);
        BagInventory.verify(f.local,f.ms);
        assertThrows(Exception.class,()->BagInventory.requireAvailable(f.local,f.ms,f.cycle,1));
        assertThrows(Exception.class,()->SyncRules.grant(f.ms,f.local,f.two,f.cycle,f.family,f.m.getPrivate()));
        cycle.put("stockTotal",2);
        assertThrows(Exception.class,()->BagInventory.verify(f.local,f.ms));
        cycle.put("stockTotal",3);
        BagInventory.verify(f.local,f.ms);
    }
    @Test public void correctsAndRejectsOverIssuedDirectDelivery()throws Exception{
        SyncRulesTest.Fixture f=new SyncRulesTest.Fixture();
        f.local.getJSONArray("cycles").getJSONObject(0).put("stockTotal",2).put("stockWarningAt",1);
        f.local.getJSONArray("deliveries").put(new JSONObject().put("c",f.cycle).put("f",f.family).put("q",2));
        BagInventory.verify(f.local,f.ms);
        f.local.getJSONArray("deliveries").put(new JSONObject().put("c",f.cycle).put("f",f.other).put("q",1));
        assertThrows(IllegalStateException.class,()->BagInventory.verify(f.local,f.ms));
        f.local.getJSONArray("deliveries").remove(1);
        BagInventory.verify(f.local,f.ms);
    }
    @Test public void oldBagsRemainReadableButNewGrantsNeedConfiguredTotal()throws Exception{
        SyncRulesTest.Fixture f=new SyncRulesTest.Fixture();
        f.local.getJSONArray("cycles").getJSONObject(0).remove("stockTotal");
        BagInventory.verify(f.local,f.ms);
        assertNull(BagInventory.limit(f.local.getJSONArray("cycles").getJSONObject(0)));
        assertThrows(IllegalStateException.class,()->f.grant());
        JSONObject c=f.local.getJSONArray("cycles").getJSONObject(0);
        c.put("stockTotal",5).put("stockWarningAt",6);
        assertThrows(IllegalArgumentException.class,()->BagInventory.verify(f.local,f.ms));
        c.put("stockWarningAt",0);
        BagInventory.verify(f.local,f.ms);
        for(Object bad:new Object[]{"",0,-1,"5.5","hello",1000001}){
            c.put("stockTotal",bad);
            if(!"".equals(bad))assertThrows(Exception.class,()->BagInventory.verify(f.local,f.ms));
        }
    }
}
