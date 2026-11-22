package ye.gaz.distribution;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.util.HashSet;
import java.util.Set;

final class RafdLicense {
    private final SharedPreferences prefs;
    final String code;
    RafdLicense(Context c)throws Exception{
        prefs=c.getSharedPreferences("rafd_license_v1",Context.MODE_PRIVATE);
        code=RafdPermit.deviceCode(Settings.Secure.getString(c.getContentResolver(),Settings.Secure.ANDROID_ID));
    }
    private long now(){return System.currentTimeMillis()/1000L;}
    private boolean stopped(String id){return prefs.getStringSet("stoppedPermitIds",java.util.Collections.emptySet()).contains(id);}
    synchronized RafdPermit verify(String role)throws Exception {
        long n=now(),last=prefs.getLong("lastSeen",0L);
        RafdPermit p=RafdPermit.verify(prefs.getString("permit",""),code,RafdPermit.ownerKey(),n,last);
        if(!p.allowsRole(role))throw new SecurityException("Role mismatch");
        if(stopped(p.id))throw new SecurityException("Permit stopped");
        if(n>last+60&&!prefs.edit().putLong("lastSeen",n).commit())throw new IllegalStateException("Clock state save failed");
        return p;
    }
    synchronized RafdPermit install(String token,String role)throws Exception{
        long n=now(),last=prefs.getLong("lastSeen",0L);
        String clean=token==null?"":token.replaceAll("\\s+","");
        RafdPermit p=RafdPermit.verify(clean,code,RafdPermit.ownerKey(),n,last);
        if(!p.allowsRole(role))throw new SecurityException("Role mismatch");
        if(stopped(p.id))throw new SecurityException("Permit stopped");
        if(!prefs.edit().putString("permit",clean).putLong("lastSeen",Math.max(n,last)).commit())throw new IllegalStateException("Permit save failed");
        return p;
    }
    synchronized boolean importStop(String signedStop)throws Exception{
        RafdRevocation stop=RafdRevocation.verify(signedStop==null?"":signedStop.replaceAll("\\s+",""),code,RafdPermit.ownerKey(),now());
        Set<String> stopped=new HashSet<>(prefs.getStringSet("stoppedPermitIds",java.util.Collections.emptySet()));
        if(!stopped.contains(stop.permitId)&&stopped.size()>=2000)throw new SecurityException("Too many stop notices");
        stopped.add(stop.permitId);
        if(!prefs.edit().putStringSet("stoppedPermitIds",stopped).commit())throw new IllegalStateException("Stop save failed");
        try{RafdPermit active=RafdPermit.verify(prefs.getString("permit",""),code,RafdPermit.ownerKey(),now(),prefs.getLong("lastSeen",0L));return stop.permitId.equals(active.id);}catch(Exception ignored){return false;}
    }
}
