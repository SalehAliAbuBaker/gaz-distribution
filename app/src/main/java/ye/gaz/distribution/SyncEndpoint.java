package ye.gaz.distribution;
import org.json.JSONObject;
import java.security.PrivateKey;
final class SyncEndpoint {
    static String sign(JSONObject s,String ip,PrivateKey secret)throws Exception{
        PairPackets.require("mother".equals(s.getString("role")),"Mother required");GazLan.localAddress(ip);
        return SyncWire.sign(new JSONObject().put("v",1).put("type","endpoint").put("mother",s.getString("device")).put("deployment",s.getString("deployment")).put("epoch",s.getString("epoch")).put("ip",ip).put("port",GazLan.PORT),secret);
    }
    static String read(JSONObject s,String packet)throws Exception{
        PairPackets.require("distributor".equals(s.getString("role"))&&packet!=null&&packet.length()<=2000,"Invalid endpoint");
        JSONObject p=SyncWire.verify(packet,s.getString("motherPub"),"endpoint");
        for(String k:new String[]{"mother","deployment","epoch"})PairPackets.require(s.getString(k).equals(p.getString(k)),"Wrong mother or neighborhood");
        PairPackets.require(p.getInt("port")==GazLan.PORT,"Invalid port");return GazLan.localAddress(p.getString("ip")).getHostAddress();
    }
}
