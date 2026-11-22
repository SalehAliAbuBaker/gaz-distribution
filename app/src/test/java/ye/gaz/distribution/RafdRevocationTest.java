package ye.gaz.distribution;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class RafdRevocationTest {
    private static String sign(PrivateKey privateKey, String device,String permitId,long iat)throws Exception{
        JSONObject payload=new JSONObject().put("v",1).put("app","rafd").put("action","stop")
            .put("permit",permitId).put("device",device).put("iat",iat).put("reason","إيقاف تجريبي");
        String text="RFR1."+Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        Signature sig=Signature.getInstance("SHA256withRSA");sig.initSign(privateKey);sig.update(text.getBytes(StandardCharsets.US_ASCII));
        return text+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
    }
    @Test public void verifiesCorrectOwnerSignatureAndDevice()throws Exception{
        KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);KeyPair kp=gen.generateKeyPair();
        String device="ABCD1234ABCD1234ABCD1234ABCD1234",id=UUID.randomUUID().toString();
        long now=1800000000L;
        String token=sign(kp.getPrivate(),device,id,now);
        RafdRevocation notice=RafdRevocation.verify(token,device,kp.getPublic(),now);
        assertEquals(id,notice.permitId);assertEquals(device,notice.device);assertEquals("إيقاف تجريبي",notice.reason);
        assertThrows(SecurityException.class,()->RafdRevocation.verify(token,"00000000000000000000000000000000",kp.getPublic(),now));
        assertThrows(SecurityException.class,()->RafdRevocation.verify(token,device,kp.getPublic(),now-1000));
        String tampered=token.replace("RFR1.","RFR2.");
        assertThrows(SecurityException.class,()->RafdRevocation.verify(tampered,device,kp.getPublic(),now));
        KeyPair wrong=gen.generateKeyPair();
        assertThrows(SecurityException.class,()->RafdRevocation.verify(token,device,wrong.getPublic(),now));
    }
}