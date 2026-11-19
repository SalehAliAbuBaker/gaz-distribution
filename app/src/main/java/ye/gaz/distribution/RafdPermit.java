package ye.gaz.distribution;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

final class RafdPermit {
    static final String PUBLIC_KEY="MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAzXiXm47zRMXqyLTtjVgyJ81ksdey3LTyFJ+FSSB4zBJd4a8Q3+O/yUUCXlP7Dq9LC+kTDgwln722qtDguCiiQRVfIlZecfiaFqxjRGFdnqn6pTU2SHIF5G3zFtsICossg/Xw2F7Od+nUFbiTdIF1QG9ZLAq3pehlcg9fpzcxuB9ff40vI7Rw90t3aG9uSIkCb0KTmlfHNDiBUjZkdaTvrvRslsml5kAtXUbXVDsURVa/RnW5lOMHBfbnzPMz3jDpXIdCCyz1Exfk+IJG9UGAGIAmqK+rb07htP7NBt7l3lkZGnuKRxtr+GDkRj02HNKRpvSrZF/9PcmObtNJAIhyj9lnGb345DX9MoaAXtDY+b+Vtx8SdwBxELe2ugvKiIQ5MiJCYw+cKJ8DJ8gE+Ses4bMOmNYhLWWYK9q8Ew3FEMUcPnXBKVRMaVOXqFqQlfDD9hZb1hBFUahQ6CUuueLPSuCX93N6VmYhg+wLHHBJwyuMBjOf09DryTprI0+XyQMtAgMBAAE=";
    static final long CLOCK_GRACE=600L;
    final String role,id,entity,holder;final long issued,expiry;
    private RafdPermit(JSONObject p)throws Exception {
        role=p.getString("role");id=p.getString("id");entity=p.optString("entity","");holder=p.optString("holder","");
        issued=p.getLong("iat");expiry=p.getLong("exp");
        UUID.fromString(id);
        if(!("mother".equals(role)||"distributor".equals(role))||entity.length()>120||holder.length()>120||issued<=0||expiry<0||(expiry!=0&&expiry<=issued))throw new SecurityException("Invalid permit");
    }
    static PublicKey ownerKey()throws Exception{return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));}
    static String deviceCode(String androidId)throws Exception{
        if(androidId==null||androidId.trim().isEmpty())throw new SecurityException("Device identity unavailable");
        byte[] d=MessageDigest.getInstance("SHA-256").digest(("rafd-device-v1:"+androidId).getBytes(StandardCharsets.UTF_8));
        StringBuilder b=new StringBuilder();for(int i=0;i<16;i++)b.append(String.format(Locale.ROOT,"%02X",d[i]&255));return b.toString();
    }
    static String displayCode(String code){return "RFD-"+code.replaceAll("(.{8})(?=.)","$1-");}
    static RafdPermit verify(String input,String device,PublicKey key,long now,long lastSeen)throws Exception{
        if(input==null||input.length()>8192)throw new SecurityException("Invalid code");
        String[] a=input.trim().split("\\.",-1);
        if(a.length!=3||!"RFD1".equals(a[0])||!a[1].matches("[A-Za-z0-9_-]+")||!a[2].matches("[A-Za-z0-9_-]+"))throw new SecurityException("Invalid code");
        Signature s=Signature.getInstance("SHA256withRSA");s.initVerify(key);s.update(("RFD1."+a[1]).getBytes(StandardCharsets.US_ASCII));
        if(!s.verify(Base64.getUrlDecoder().decode(a[2])))throw new SecurityException("Signature mismatch");
        JSONObject p=new JSONObject(new String(Base64.getUrlDecoder().decode(a[1]),StandardCharsets.UTF_8));
        if(p.getInt("v")!=1||!"rafd".equals(p.getString("app"))||!device.equals(p.getString("device")))throw new SecurityException("Wrong device");
        RafdPermit result=new RafdPermit(p);
        if(now<=0||now+CLOCK_GRACE<lastSeen||now+CLOCK_GRACE<result.issued)throw new SecurityException("Device clock");
        if(result.expiry!=0&&Math.max(now,lastSeen)>=result.expiry)throw new SecurityException("Permit expired");
        return result;
    }
    boolean allowsRole(String current){return "independent".equals(current)||("mother".equals(role)&&"mother".equals(current))||("distributor".equals(role)&&("pending".equals(current)||"distributor".equals(current)));}
}
