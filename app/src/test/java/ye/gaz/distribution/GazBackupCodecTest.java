package ye.gaz.distribution;

import org.junit.Test;
import static org.junit.Assert.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.json.JSONObject;

public class GazBackupCodecTest {
    private static final String JSON="{\"area\":\"حارة الروضة\",\"families\":[{\"name\":\"أسرة تجريبية\"}]}";
    private static final char[] PASS="test-password-only".toCharArray();
    @Test public void gzipJsonEnvelopeAndArabicRoundTrip()throws Exception{
        byte[] data=GazBackupCodec.encode(JSON,PASS);
        assertEquals(0x1f,data[0]&255);assertEquals(0x8b,data[1]&255);
        String outer=new String(GazBackupCodec.gunzip(data,1024*1024),StandardCharsets.UTF_8);
        JSONObject env=new JSONObject(outer);assertEquals(2,env.getInt("version"));assertFalse(outer.contains("الروضة"));
        assertEquals(JSON,GazBackupCodec.decode(data,PASS));
    }
    @Test public void wrongPasswordAndTamperedCiphertextRejected()throws Exception{
        byte[] data=GazBackupCodec.encode(JSON,PASS);
        assertThrows(Exception.class,()->GazBackupCodec.decode(data,"wrong-password".toCharArray()));
        JSONObject env=new JSONObject(new String(GazBackupCodec.gunzip(data,1024*1024),StandardCharsets.UTF_8));
        byte[] body=Base64.getDecoder().decode(env.getString("payload"));body[0]^=1;
        env.put("payload",Base64.getEncoder().encodeToString(body));
        byte[] changed=GazBackupCodec.gzip(env.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class,()->GazBackupCodec.decode(changed,PASS));
    }
    @Test public void compressedDataIsMuchSmallerForRepeatedRecords()throws Exception{
        String large="{\"data\":\""+new String(new char[200000]).replace('\0','a')+"\"}";
        byte[] data=GazBackupCodec.encode(large,PASS);
        assertTrue(data.length<large.length()/20);assertEquals(large,GazBackupCodec.decode(data,PASS));
    }
    @Test public void legacyGazbakCompatible()throws Exception{
        byte[] magic={'G','A','Z','B',1},salt=new byte[16],iv=new byte[12];
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(PASS,salt,310000,256)).getEncoded(),"AES"),new GCMParameterSpec(128,iv));c.updateAAD(magic);
        byte[] encrypted=c.doFinal(JSON.getBytes(StandardCharsets.UTF_8));
        byte[] data=ByteBuffer.allocate(33+encrypted.length).put(magic).put(salt).put(iv).put(encrypted).array();
        assertEquals(JSON,GazBackupCodec.decode(data,PASS));
    }
    @Test public void decompressionLimitAndInvalidFormat()throws Exception{
        byte[] data=GazBackupCodec.gzip(new byte[1024]);
        assertThrows(IOException.class,()->GazBackupCodec.gunzip(data,100));
        assertThrows(Exception.class,()->GazBackupCodec.decode(new byte[50],PASS));
    }
}
