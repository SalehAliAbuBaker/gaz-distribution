package ye.gaz.distribution;

import org.json.*;
import java.io.*;
import java.security.*;
import java.nio.charset.StandardCharsets;

/** Domain-separated ECDSA messages, framed over a pinned TLS channel. */
final class SyncWire {
    static final int MAX=8*1024*1024;
    static final String PREFIX="GAZSYNC1";
    static String sign(JSONObject p,PrivateKey secret)throws Exception{
        String t=PREFIX+"."+PairPackets.b64(PairPackets.utf(p.toString()));
        Signature sig=Signature.getInstance("SHA256withECDSA");sig.initSign(secret);sig.update(PairPackets.utf(t));
        String result=t+"."+PairPackets.b64(sig.sign());PairPackets.require(result.length()<=MAX,"Packet too large");return result;
    }
    static JSONObject peek(String packet)throws Exception{
        PairPackets.require(packet!=null&&packet.length()<=MAX,"Invalid packet size");String[] a=packet.split("\\.",-1);
        PairPackets.require(a.length==3&&PREFIX.equals(a[0]),"Wrong protocol");
        return new JSONObject(new String(PairPackets.bytes(a[1]),StandardCharsets.UTF_8));
    }
    static JSONObject verify(String packet,String pub,String type)throws Exception{
        JSONObject p=peek(packet);String[] a=packet.split("\\.",-1);
        Signature sig=Signature.getInstance("SHA256withECDSA");sig.initVerify(PairPackets.key(pub));sig.update(PairPackets.utf(a[0]+"."+a[1]));
        PairPackets.require(sig.verify(PairPackets.bytes(a[2]))&&p.getInt("v")==1&&type.equals(p.getString("type")),"Invalid signature/type");return p;
    }
    static void send(DataOutputStream out,String text)throws Exception{
        byte[] b=PairPackets.utf(text);PairPackets.require(b.length>0&&b.length<=MAX,"Frame too large");out.writeInt(b.length);out.write(b);out.flush();
    }
    static String receive(DataInputStream in)throws Exception{
        int n=in.readInt();PairPackets.require(n>0&&n<=MAX,"Invalid frame");byte[] b=new byte[n];in.readFully(b);return new String(b,StandardCharsets.UTF_8);
    }
}
