package ye.gaz.distribution;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.JSONObject;
import java.security.*;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
public class RafdPermitTest {
    private static final long NOW=1800000000L;
    private static final String DEVICE="0123456789ABCDEF0123456789ABCDEF";
    private String token(KeyPair kp,String device,String role,long iat,long exp)throws Exception{
        JSONObject p=new JSONObject().put("v",1).put("app","rafd").put("device",device).put("role",role).put("iat",iat).put("exp",exp).put("id",UUID.randomUUID().toString());
        String prefix="RFD1."+Base64.getUrlEncoder().withoutPadding().encodeToString(p.toString().getBytes(StandardCharsets.UTF_8));Signature s=Signature.getInstance("SHA256withRSA");s.initSign(kp.getPrivate());s.update(prefix.getBytes(StandardCharsets.US_ASCII));return prefix+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
    }
    @Test public void checksSignatureDeviceAndClock()throws Exception{
        KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);KeyPair k=g.generateKeyPair(),other=g.generateKeyPair();
        String valid=token(k,DEVICE,"mother",NOW-60,NOW+3600);RafdPermit p=RafdPermit.verify(valid,DEVICE,k.getPublic(),NOW,0);
        assertTrue(p.allowsRole("mother"));assertFalse(p.allowsRole("distributor"));assertTrue(p.allowsRole("independent"));
        assertThrows(Exception.class,()->RafdPermit.verify(valid,"00000000000000000000000000000000",k.getPublic(),NOW,0));
        assertThrows(Exception.class,()->RafdPermit.verify(valid,DEVICE,other.getPublic(),NOW,0));
        assertThrows(Exception.class,()->RafdPermit.verify(valid,DEVICE,k.getPublic(),NOW+3600,0));
        assertThrows(Exception.class,()->RafdPermit.verify(valid,DEVICE,k.getPublic(),NOW,NOW+601));
        assertThrows(Exception.class,()->RafdPermit.verify(token(k,DEVICE,"mother",NOW+601,0),DEVICE,k.getPublic(),NOW,0));
        String forever=token(k,DEVICE,"distributor",NOW-1,0);RafdPermit q=RafdPermit.verify(forever,DEVICE,k.getPublic(),NOW+999999,0);
        assertTrue(q.allowsRole("distributor"));assertTrue(q.allowsRole("pending"));assertFalse(q.allowsRole("mother"));
        assertEquals(32,RafdPermit.deviceCode("test-android-id").length());assertNotEquals(RafdPermit.deviceCode("A"),RafdPermit.deviceCode("B"));
        assertNotNull(RafdPermit.ownerKey());
    }
}
