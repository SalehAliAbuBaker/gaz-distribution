package ye.gaz.distribution;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class RafdMoneyTest {
  @Test public void snapshotsAndLegacy()throws Exception{
    JSONObject bag=new JSONObject().put("valueKind","cash").put("currency","YER").put("unitMinor",1500000L);
    JSONObject d=new JSONObject().put("q",3);
    RafdMoney.attach(d,bag,3);
    assertEquals(4500000L,d.getLong("amountMinor"));
    RafdMoney.checkDelivery(d);
    JSONObject old=new JSONObject().put("q",3);
    RafdMoney.checkDelivery(old);
    assertFalse(old.has("amountMinor"));
    assertThrows(JSONException.class,()->RafdMoney.checkCycle(new JSONObject().put("valueKind","cash")));
    assertThrows(JSONException.class,()->RafdMoney.checkCycle(new JSONObject().put("valueKind","none").put("unitMinor",1)));
    assertThrows(JSONException.class,()->RafdMoney.checkDelivery(new JSONObject().put("amountMinor",2)));
  }
}