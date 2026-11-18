package ye.gaz.distribution;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.cert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Ephemeral TLS key certified with the existing, non-exportable pairing key. */
final class LanCertificate {
    static final class Credentials {
        final PrivateKey key;final X509Certificate cert;
        Credentials(PrivateKey k,X509Certificate c){key=k;cert=c;}
    }
    private static byte[] cat(byte[]... parts)throws Exception{ByteArrayOutputStream o=new ByteArrayOutputStream();for(byte[] p:parts)o.write(p);return o.toByteArray();}
    private static byte[] der(int tag,byte[] body)throws Exception{
        ByteArrayOutputStream o=new ByteArrayOutputStream();o.write(tag);int n=body.length;
        if(n<128)o.write(n);else{int c=0;for(int i=n;i>0;i>>>=8)c++;o.write(128|c);for(int i=c-1;i>=0;i--)o.write((n>>>(8*i))&255);}
        o.write(body);return o.toByteArray();
    }
    private static byte[] seq(byte[]... p)throws Exception{return der(48,cat(p));}
    private static byte[] name(String s)throws Exception{return seq(der(49,seq(new byte[]{6,3,85,4,3},der(12,s.getBytes(StandardCharsets.UTF_8)))));}
    private static byte[] time(long ms)throws Exception{SimpleDateFormat t=new SimpleDateFormat("yyyyMMddHHmmss'Z'",Locale.ROOT);t.setTimeZone(TimeZone.getTimeZone("UTC"));return der(24,t.format(new Date(ms)).getBytes(StandardCharsets.US_ASCII));}
    static Credentials create(PrivateKey identity)throws Exception{
        KeyPairGenerator g=KeyPairGenerator.getInstance("EC");g.initialize(new ECGenParameterSpec("secp256r1"));KeyPair tls=g.generateKeyPair();
        byte[] alg=seq(new byte[]{6,8,42,(byte)134,72,(byte)206,61,4,3,2});
        byte[] serial=new byte[16];new SecureRandom().nextBytes(serial);serial[0]=(byte)((serial[0]&63)|64);
        long now=System.currentTimeMillis();
        byte[] tbs=seq(der(160,der(2,new byte[]{2})),der(2,serial),alg,name("GAZ paired authority"),seq(time(now-3600000L),time(now+86400000L)),name("GAZ temporary LAN"),tls.getPublic().getEncoded());
        Signature sig=Signature.getInstance("SHA256withECDSA");sig.initSign(identity);sig.update(tbs);
        byte[] encoded=seq(tbs,alg,der(3,cat(new byte[]{0},sig.sign())));
        return new Credentials(tls.getPrivate(),(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encoded)));
    }
    static void verify(X509Certificate c,PublicKey authority)throws GeneralSecurityException{
        c.checkValidity();if(!"1.2.840.10045.4.3.2".equals(c.getSigAlgOID())||c.getBasicConstraints()!=-1)throw new CertificateException("Invalid LAN certificate");c.verify(authority);
    }
}
