package ye.gaz.distribution;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
final class RafdRevocation {
    final String permitId,device,reason;final long issued;
    private RafdRevocation(JSONObject j)throws Exception{
        permitId=j.getString("permit");device=j.getString("device");issued=j.getLong("iat");reason=j.optString("reason","");
        UUID.fromString(permitId);
        if(!device.matches("[0-9A-F]{32}")||issued<=0||reason.length()>300||reason.isEmpty())
            throw new SecurityException("Invalid stop certificate");
    }
    static RafdRevocation verify(String raw,String expectedDevice,PublicKey key,long now)throws Exception{
        if(raw==null||raw.length()>8192)throw new SecurityException("Invalid stop code");
        String[] a=raw.trim().split("\\.",-1);
        if(a.length!=3||!"RFR1".equals(a[0])||
           !a[1].matches("[A-Za-z0-9_-]+")||!a[2].matches("[A-Za-z0-9_-]+"))
            throw new SecurityException("Invalid stop code");
        Signature signature=Signature.getInstance("SHA256withRSA");
        signature.initVerify(key);signature.update(("RFR1."+a[1]).getBytes(StandardCharsets.US_ASCII));
        if(!signature.verify(Base64.getUrlDecoder().decode(a[2])))throw new SecurityException("Stop signature mismatch");
        JSONObject j=new JSONObject(new String(Base64.getUrlDecoder().decode(a[1]),StandardCharsets.UTF_8));
        if(j.getInt("v")!=1||!"rafd".equals(j.getString("app"))||!"stop".equals(j.getString("action")))
            throw new SecurityException("Invalid stop code");
        RafdRevocation result=new RafdRevocation(j);
        if(!expectedDevice.equals(result.device))throw new SecurityException("Wrong device");
        if(now<=0||now+RafdPermit.CLOCK_GRACE<result.issued)throw new SecurityException("Device clock");
        return result;
    }
}
