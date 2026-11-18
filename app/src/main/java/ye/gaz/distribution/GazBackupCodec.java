package ye.gaz.distribution;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.zip.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.JSONObject;

/** GZIP containing JSON envelope; the actual records are compressed before encryption. */
final class GazBackupCodec {
    private static final int MAX=64*1024*1024, MAX_ENVELOPE=96*1024*1024;
    private static final byte[] LEGACY={'G','A','Z','B',1};
    private static final String FORMAT="gaz-backup", CIPHER="AES-256-GCM", KDF="PBKDF2-HMAC-SHA256";
    private static final byte[] AAD="gaz-backup/v2|gzip|AES-256-GCM|PBKDF2-HMAC-SHA256|310000".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RNG=new SecureRandom();
    private static void require(boolean ok,String msg)throws IOException{if(!ok)throw new IOException(msg);}
    private static byte[] utf(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static SecretKey key(char[] pass,byte[] salt)throws Exception{
        PBEKeySpec spec=new PBEKeySpec(pass,salt,310000,256);
        try{return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(),"AES");}finally{spec.clearPassword();}
    }
    static byte[] gzip(byte[] src)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(GZIPOutputStream gz=new GZIPOutputStream(out)){gz.write(src);}return out.toByteArray();
    }
    static byte[] gunzip(byte[] src,int limit)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(GZIPInputStream gz=new GZIPInputStream(new ByteArrayInputStream(src))){
            byte[] buffer=new byte[16384];int n;while((n=gz.read(buffer))!=-1){require(n<=limit-out.size(),"Decompressed backup too large");out.write(buffer,0,n);}
        }return out.toByteArray();
    }
    private static String b64(byte[] b){return Base64.getEncoder().encodeToString(b);}
    private static byte[] unb64(String s){return Base64.getDecoder().decode(s);}
    static byte[] encode(String json,char[] pass)throws Exception{
        byte[] raw=utf(json);require(raw.length<=MAX,"Backup too large");
        byte[] salt=new byte[16],iv=new byte[12];RNG.nextBytes(salt);RNG.nextBytes(iv);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(pass,salt),new GCMParameterSpec(128,iv));cipher.updateAAD(AAD);
        byte[] compressed=gzip(raw);byte[] body=cipher.doFinal(compressed);
        JSONObject envelope=new JSONObject().put("format",FORMAT).put("version",2).put("cipher",CIPHER).put("kdf",KDF).put("iterations",310000).put("compression","gzip").put("salt",b64(salt)).put("iv",b64(iv)).put("payload",b64(body));
        byte[] encoded=gzip(utf(envelope.toString()));require(encoded.length<=MAX,"Backup file too large");return encoded;
    }
    static String decode(byte[] src,char[] pass)throws Exception{
        require(src.length>=2&&src.length<=MAX,"Invalid backup size");
        if((src[0]&255)==0x1f&&(src[1]&255)==0x8b){
            JSONObject env=new JSONObject(new String(gunzip(src,MAX_ENVELOPE),StandardCharsets.UTF_8));
            require(env.length()==9&&FORMAT.equals(env.getString("format"))&&env.getInt("version")==2&&CIPHER.equals(env.getString("cipher"))&&KDF.equals(env.getString("kdf"))&&env.getInt("iterations")==310000&&"gzip".equals(env.getString("compression")),"Unknown backup format");
            byte[] salt=unb64(env.getString("salt")),iv=unb64(env.getString("iv")),body=unb64(env.getString("payload"));
            require(salt.length==16&&iv.length==12&&body.length>=16&&body.length<=MAX+65536,"Invalid ciphertext");
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(pass,salt),new GCMParameterSpec(128,iv));c.updateAAD(AAD);
            return new String(gunzip(c.doFinal(body),MAX),StandardCharsets.UTF_8);
        }
        require(src.length>=49,"Truncated legacy backup");
        ByteBuffer b=ByteBuffer.wrap(src);byte[] magic=new byte[5],salt=new byte[16],iv=new byte[12];b.get(magic);b.get(salt);b.get(iv);
        require(Arrays.equals(LEGACY,magic),"Unknown backup format");
        byte[] payload=new byte[b.remaining()];b.get(payload);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(pass,salt),new GCMParameterSpec(128,iv));c.updateAAD(LEGACY);
        return new String(c.doFinal(payload),StandardCharsets.UTF_8);
    }
}
