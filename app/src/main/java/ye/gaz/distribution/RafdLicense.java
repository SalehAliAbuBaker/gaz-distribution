package ye.gaz.distribution;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

final class RafdLicense {
    private final SharedPreferences prefs;
    final String code;
    RafdLicense(Context c)throws Exception{
        prefs=c.getSharedPreferences("rafd_license_v1",Context.MODE_PRIVATE);
        code=RafdPermit.deviceCode(Settings.Secure.getString(c.getContentResolver(),Settings.Secure.ANDROID_ID));
    }
    private long now(){return System.currentTimeMillis()/1000L;}
    synchronized RafdPermit verify(String role)throws Exception {
        long n=now(),last=prefs.getLong("lastSeen",0L);
        RafdPermit p=RafdPermit.verify(prefs.getString("permit",""),code,RafdPermit.ownerKey(),n,last);
        if(!p.allowsRole(role))throw new SecurityException("Role mismatch");
        if(n>last+60&&!prefs.edit().putLong("lastSeen",n).commit())throw new IllegalStateException("Clock state save failed");
        return p;
    }
    synchronized RafdPermit install(String token,String role)throws Exception{
        long n=now(),last=prefs.getLong("lastSeen",0L);
        String clean=token==null?"":token.replaceAll("\\s+","");
        RafdPermit p=RafdPermit.verify(clean,code,RafdPermit.ownerKey(),n,last);
        if(!p.allowsRole(role))throw new SecurityException("Role mismatch");
        if(!prefs.edit().putString("permit",clean).putLong("lastSeen",Math.max(n,last)).commit())throw new IllegalStateException("Permit save failed");
        return p;
    }
}
