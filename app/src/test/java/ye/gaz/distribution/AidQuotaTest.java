package ye.gaz.distribution;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class AidQuotaTest {
    private JSONObject cycle(Object two,Object three)throws Exception{return new JSONObject().put("quota",1).put("aidTwoAbove",two).put("aidThreeAbove",three);}
    private int q(JSONObject c,int n)throws Exception{return AidQuota.forFamily(c,new JSONObject().put("members",n));}
    @Test public void strictBoundaries()throws Exception{JSONObject c=cycle("5","9");assertEquals(1,q(c,5));assertEquals(2,q(c,6));assertEquals(2,q(c,9));assertEquals(3,q(c,10));}
    @Test public void emptyAndLegacy()throws Exception{assertEquals(1,q(cycle("",""),99));assertEquals(1,q(cycle("","9"),8));assertEquals(3,q(cycle("","9"),10));assertEquals(2,q(cycle("5",""),20));assertEquals(4,q(new JSONObject().put("quota",4),10));}
    @Test public void invalidRules()throws Exception{assertThrows(IllegalArgumentException.class,()->AidQuota.validate(cycle("5","5")));assertThrows(IllegalArgumentException.class,()->AidQuota.validate(cycle("8","4")));assertThrows(IllegalArgumentException.class,()->AidQuota.validate(cycle("-1","")));assertThrows(IllegalArgumentException.class,()->AidQuota.validate(cycle("2.5","")));assertThrows(IllegalArgumentException.class,()->AidQuota.validate(cycle("5","9").put("quota",2)));}
}
