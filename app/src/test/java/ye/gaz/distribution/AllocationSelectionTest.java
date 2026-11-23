package ye.gaz.distribution;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.UUID;

public class AllocationSelectionTest{
    static String id(){return UUID.randomUUID().toString();}
    static JSONObject family(String id,int no,String square)throws Exception{return new JSONObject().put("id",id).put("no",no).put("name","أحمد "+no).put("house","١٢").put("square",square);}
    @Test public void excludesReservedAndDeliveredAndSelectsGroups()throws Exception{
        String cycle=id(),a=id(),b=id(),c=id(),d=id(),e=id();
        JSONObject local=new JSONObject().put("cycles",new JSONArray().put(new JSONObject().put("id",cycle).put("closed",false)))
            .put("families",new JSONArray().put(family(a,1,"١")).put(family(b,2,"1")).put(family(c,3,"2")).put(family(d,4,"1")).put(family(e,5,"")))
            .put("deliveries",new JSONArray().put(new JSONObject().put("c",cycle).put("f",d)));
        JSONObject grants=new JSONObject().put(SyncRules.key(cycle,c),"signed");
        AllocationSelection model=new AllocationSelection(local,grants,cycle);
        assertEquals(3,model.total());assertEquals(2,model.visible("احمد 12","1").size());
        model.setAll(model.visible("","1"),true);assertEquals(2,model.count());
        model.setAll(model.visible("",""),true);assertEquals(3,model.count());
        model.set(c,true);model.set(d,true);assertEquals(3,model.count());
        model.setAll(model.visible("","1"),false);assertEquals(java.util.List.of(e),model.ids());
        model.setAll(model.visible("",null),true);assertEquals(3,model.count());model.clear();assertEquals(0,model.count());
    }
    @Test public void tieredAllocationListsUnknownFamilyButCannotSelectIt()throws Exception{
        String cycle=id(),a=id(),b=id();
        JSONObject c=new JSONObject().put("id",cycle).put("closed",false).put("quota",1).put("aidTwoAbove","5").put("aidThreeAbove","9");
        JSONObject known=family(a,1,"1").put("members",6),unknown=family(b,2,"1").put("members",JSONObject.NULL);
        JSONObject local=new JSONObject().put("cycles",new JSONArray().put(c))
            .put("families",new JSONArray().put(known).put(unknown)).put("deliveries",new JSONArray());
        AllocationSelection model=new AllocationSelection(local,new JSONObject(),cycle);
        assertEquals(2,model.total());assertEquals(2,model.visible("","1").size());
        model.setAll(model.visible("","1"),true);assertEquals(1,model.count());
        assertEquals(java.util.List.of(a),model.ids());
        model.set(b,true);assertFalse(model.checked(b));
    }
}
