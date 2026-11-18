package ye.gaz.distribution;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/** Offline QR messages: public identity only, never credentials or beneficiary data. */
final class PairPackets {
    static final String PREFIX="GAZPAIR1";
    private static final Base64.Encoder EN=Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DE=Base64.getUrlDecoder();
    static String b64(byte[] bytes){return EN.encodeToString(bytes);}
    static byte[] bytes(String text){return DE.decode(text);}
    static byte[] utf(String text){return text.getBytes(StandardCharsets.UTF_8);}
    static String digest(byte[] data)throws Exception{
        StringBuilder hex=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hex.toString();
    }
    static String fingerprint(String pub)throws Exception{
        PublicKey key=key(pub);return digest(key.getEncoded());
    }
    static String shortFingerprint(String pub)throws Exception{
        String f=fingerprint(pub).substring(0,16).toUpperCase(java.util.Locale.ROOT);
        return f.substring(0,4)+" "+f.substring(4,8)+" "+f.substring(8,12)+" "+f.substring(12,16);
    }
    private static PublicKey key(String pub)throws Exception{
        if(pub.length()>250)throw new GeneralSecurityException("Public key too long");
        PublicKey key=KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(bytes(pub)));
        if(!(key instanceof ECPublicKey)||((ECPublicKey)key).getParams().getCurve().getField().getFieldSize()!=256)throw new GeneralSecurityException("Wrong EC key");
        return key;
    }
    static void uuid(String value)throws Exception{if(!UUID.fromString(value).toString().equals(value))throw new GeneralSecurityException("Invalid ID");}
    static void require(boolean condition,String reason)throws Exception{if(!condition)throw new GeneralSecurityException(reason);}
    static String pack(JSONObject p,PrivateKey secret)throws Exception{
        String body=b64(utf(p.toString()));String data=PREFIX+"."+body;
        Signature s=Signature.getInstance("SHA256withECDSA");s.initSign(secret);s.update(utf(data));return data+"."+b64(s.sign());
    }
    static JSONObject unpack(String packet,String type)throws Exception{
        require(packet!=null&&packet.length()<=2400,"Invalid QR size");
        String[] parts=packet.split("\\.",-1);require(parts.length==3&&PREFIX.equals(parts[0]),"Invalid protocol");
        JSONObject p=new JSONObject(new String(bytes(parts[1]),StandardCharsets.UTF_8));
        require(p.getInt("v")==1&&type.equals(p.getString("type")),"Wrong message type");
        PublicKey pub=key(p.getString("pub"));Signature sig=Signature.getInstance("SHA256withECDSA");
        sig.initVerify(pub);sig.update(utf(parts[0]+"."+parts[1]));require(sig.verify(bytes(parts[2])),"Invalid signature");
        Set<String> expected=type.equals("request")?Set.of("v","type","device","nonce","name","pub"):Set.of("v","type","device","request","mother","deployment","epoch","area","pub");
        require(p.length()==expected.size()&&p.keySet().equals(expected),"Unexpected fields");
        uuid(p.getString("device"));
        if(type.equals("request")){uuid(p.getString("nonce"));String name=p.getString("name");require(!name.trim().isEmpty()&&name.length()<=60,"Invalid device name");}
        else {uuid(p.getString("mother"));uuid(p.getString("deployment"));uuid(p.getString("epoch"));require(!p.getString("mother").equals(p.getString("device")),"Invalid mother");require(p.getString("request").matches("[0-9a-f]{64}"),"Invalid request reference");String area=p.getString("area");require(!area.trim().isEmpty()&&area.length()<=100,"Invalid neighborhood");}
        return p;
    }
    static JSONObject matchResponse(String response,String request,String ownDevice,String ownPub)throws Exception{
        JSONObject r=unpack(request,"request"),p=unpack(response,"approval");
        require(ownDevice.equals(r.getString("device"))&&ownPub.equals(r.getString("pub")),"Wrong local request");
        require(ownDevice.equals(p.getString("device"))&&digest(utf(request)).equals(p.getString("request")),"Approval not for this request");
        return p;
    }
}
