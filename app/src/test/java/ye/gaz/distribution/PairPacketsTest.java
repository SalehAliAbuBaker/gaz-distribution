package ye.gaz.distribution;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.JSONObject;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.UUID;

public class PairPacketsTest {
    private static KeyPair key()throws Exception{KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec("secp256r1"));return g.generateKeyPair();}
    private static String id(){return UUID.randomUUID().toString();}
    private static String pub(KeyPair k){return PairPackets.b64(k.getPublic().getEncoded());}
    private static JSONObject request(KeyPair k,String d)throws Exception{return new JSONObject().put("v",1).put("type","request").put("device",d).put("nonce",id()).put("name","موزع").put("pub",pub(k));}
    private static JSONObject approval(KeyPair k,String child,String req)throws Exception{return new JSONObject().put("v",1).put("type","approval").put("device",child).put("request",PairPackets.digest(PairPackets.utf(req))).put("mother",id()).put("deployment",id()).put("epoch",id()).put("area","حارة الروضة").put("pub",pub(k));}
    @Test public void roundTrip()throws Exception{
        KeyPair m=key(),d=key();String child=id();String req=PairPackets.pack(request(d,child),d.getPrivate());String resp=PairPackets.pack(approval(m,child,req),m.getPrivate());
        assertEquals("حارة الروضة",PairPackets.matchResponse(resp,req,child,pub(d)).getString("area"));
        assertEquals(64,PairPackets.fingerprint(pub(m)).length());assertEquals(19,PairPackets.shortFingerprint(pub(m)).length());
    }
    @Test public void tamperedSignatureRejected()throws Exception{
        KeyPair d=key(),wrong=key();String p=PairPackets.pack(request(d,id()),wrong.getPrivate());
        assertThrows(Exception.class,()->PairPackets.unpack(p,"request"));
    }
    @Test public void responseBoundToRequest()throws Exception{
        KeyPair d=key(),m=key();String child=id();String first=PairPackets.pack(request(d,child),d.getPrivate()),second=PairPackets.pack(request(d,child),d.getPrivate());String resp=PairPackets.pack(approval(m,child,first),m.getPrivate());
        assertThrows(Exception.class,()->PairPackets.matchResponse(resp,second,child,pub(d)));
        assertThrows(Exception.class,()->PairPackets.matchResponse(resp,first,id(),pub(d)));
    }
    @Test public void wrongIdentityRejected()throws Exception{
        KeyPair d=key(),m=key(),other=key();String child=id(),req=PairPackets.pack(request(d,child),d.getPrivate()),resp=PairPackets.pack(approval(m,child,req),m.getPrivate());
        assertThrows(Exception.class,()->PairPackets.matchResponse(resp,req,child,pub(other)));
        assertThrows(Exception.class,()->PairPackets.unpack(resp,"request"));
    }
    @Test public void malformedInputRejected()throws Exception{assertThrows(Exception.class,()->PairPackets.unpack("not-a-qr","request"));}
}
