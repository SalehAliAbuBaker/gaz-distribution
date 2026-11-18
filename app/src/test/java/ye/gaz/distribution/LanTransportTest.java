package ye.gaz.distribution;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.JSONObject;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.net.*;
import javax.net.ssl.*;
import java.util.concurrent.*;
public class LanTransportTest {
    static KeyPair key()throws Exception{KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec("secp256r1"));return g.generateKeyPair();}
    @Test public void certifiedTemporaryKey()throws Exception{KeyPair m=key(),x=key();LanCertificate.Credentials c=LanCertificate.create(m.getPrivate());LanCertificate.verify(c.cert,m.getPublic());assertFalse(java.util.Arrays.equals(m.getPublic().getEncoded(),c.cert.getPublicKey().getEncoded()));assertThrows(GeneralSecurityException.class,()->LanCertificate.verify(c.cert,x.getPublic()));}
    @Test public void actualTlsAndWrongAuthority()throws Exception{
        KeyPair m=key(),other=key();
        for(KeyPair id:new KeyPair[]{m,other}){
            boolean good=id==m;
            try(SSLServerSocket server=(SSLServerSocket)GazLan.serverContext(m.getPrivate()).getServerSocketFactory().createServerSocket(0,1,InetAddress.getLoopbackAddress())){
                server.setEnabledProtocols(new String[]{"TLSv1.2"});server.setSoTimeout(4000);ExecutorService pool=Executors.newSingleThreadExecutor();
                try{Future<Boolean> done=pool.submit(()->{try(SSLSocket s=(SSLSocket)server.accept()){s.setSoTimeout(5000);s.startHandshake();s.getOutputStream().write(42);return true;}catch(Exception e){return false;}});
                    try(SSLSocket s=(SSLSocket)GazLan.clientContext(PairPackets.b64(id.getPublic().getEncoded())).getSocketFactory().createSocket(InetAddress.getLoopbackAddress(),server.getLocalPort())){
                        s.setEnabledProtocols(new String[]{"TLSv1.2"});s.setSoTimeout(5000);if(good){s.startHandshake();assertEquals(42,s.getInputStream().read());}else assertThrows(SSLException.class,s::startHandshake);
                    }assertEquals(good,done.get(7,TimeUnit.SECONDS));
                }finally{pool.shutdownNow();}
            }
        }
    }
    @Test public void signedLocatorRejectsWrongMother()throws Exception{
        KeyPair m=key(),x=key();String d=java.util.UUID.randomUUID().toString(),e=java.util.UUID.randomUUID().toString(),id=java.util.UUID.randomUUID().toString();
        JSONObject a=new JSONObject().put("role","mother").put("device",id).put("deployment",d).put("epoch",e);
        JSONObject b=new JSONObject().put("role","distributor").put("mother",id).put("motherPub",PairPackets.b64(m.getPublic().getEncoded())).put("deployment",d).put("epoch",e);
        String qr=SyncEndpoint.sign(a,"10.0.79.4",m.getPrivate());assertEquals("10.0.79.4",SyncEndpoint.read(b,qr));assertThrows(Exception.class,()->SyncEndpoint.read(b,SyncEndpoint.sign(a,"10.0.79.4",x.getPrivate())));b.put("epoch",java.util.UUID.randomUUID().toString());assertThrows(Exception.class,()->SyncEndpoint.read(b,qr));
    }
}
