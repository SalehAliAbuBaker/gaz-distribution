package ye.gaz.distribution;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;
public class RafdIdentityNumberTest {
    @Test public void normalizesArDigitsAndSeparators() {
        assertEquals("00123",RafdIdentityNumber.normalize("٠٠-۱۲۳"));
        assertEquals("",RafdIdentityNumber.normalize(JSONObject.NULL));
        assertEquals("123",RafdIdentityNumber.normalize("1 2 3"));
        assertThrows(IllegalArgumentException.class,()->RafdIdentityNumber.normalize("12A4"));
        assertThrows(IllegalArgumentException.class,()->RafdIdentityNumber.normalize("1234567890123456789012345678901"));
    }
    @Test public void duplicateIdentityRejected()throws Exception {
        JSONArray f=new JSONArray().put(new JSONObject().put("identityNo","٠٠-۱۲۳"))
           .put(new JSONObject().put("identityNo","00123"));
        assertThrows(JSONException.class,()->RafdIdentityNumber.validateUnique(f));
        f.getJSONObject(1).put("identityNo","");
        RafdIdentityNumber.validateUnique(f);
    }
}