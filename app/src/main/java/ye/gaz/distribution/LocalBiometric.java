package ye.gaz.distribution;
import android.app.Activity;
import android.content.SharedPreferences;
import android.os.*;
import android.hardware.biometrics.BiometricPrompt;
import android.security.keystore.*;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import android.util.Base64;
import org.json.JSONObject;

/** A single opt-in local account per device; no password stored, per-use auth-bound key. */
final class LocalBiometric {
    static final String ALIAS="gaz-biometric-login-v1";
    private final Activity a;private final SharedPreferences prefs;private CancellationSignal signal;
    interface Result{void success(JSONObject payload);void fail(String message);}
    LocalBiometric(Activity a){this.a=a;prefs=a.getSharedPreferences("gaz_biometric",Activity.MODE_PRIVATE);}
    boolean supported(){return Build.VERSION.SDK_INT>=28;}
    boolean enabled(){return supported()&&prefs.contains("token")&&prefs.contains("iv");}
    void disable(){if(signal!=null)signal.cancel();prefs.edit().clear().commit();try{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);ks.deleteEntry(ALIAS);}catch(Exception ignored){}}
    private Cipher cipher(boolean enroll)throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(enroll){disable();KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            KeyGenParameterSpec.Builder b=new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true);
            if(Build.VERSION.SDK_INT>=30)b.setUserAuthenticationParameters(0,KeyProperties.AUTH_BIOMETRIC_STRONG);else b.setUserAuthenticationValidityDurationSeconds(-1);
            gen.init(b.build());gen.generateKey();ks.load(null);}
        SecretKey key=(SecretKey)ks.getKey(ALIAS,null);if(key==null)throw new IllegalStateException("No key");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");if(enroll)cipher.init(Cipher.ENCRYPT_MODE,key);else cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)));return cipher;
    }
    void prompt(JSONObject user,Result result){
        if(!supported()){result.fail("الدخول بالبصمة يتطلب أندرويد 9 فأحدث");return;}boolean enroll=user!=null;if(!enroll&&!enabled()){result.fail("فعّل البصمة من حسابك أولًا");return;}
        try{Cipher c=cipher(enroll);signal=new CancellationSignal();BiometricPrompt.Builder b=new BiometricPrompt.Builder(a).setTitle(enroll?"تفعيل الدخول بالبصمة":"الدخول بالبصمة").setSubtitle("تحقق بهويتك المسجلة في الجهاز").setNegativeButton("إلغاء",a.getMainExecutor(),(d,w)->{});
            if(Build.VERSION.SDK_INT>=30)b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG);
            b.build().authenticate(new BiometricPrompt.CryptoObject(c),signal,a.getMainExecutor(),new BiometricPrompt.AuthenticationCallback(){
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r){
                    try{Cipher verified=r.getCryptoObject().getCipher();if(enroll){byte[] encrypted=verified.doFinal(user.toString().getBytes(StandardCharsets.UTF_8));if(!prefs.edit().putString("token",Base64.encodeToString(encrypted,Base64.NO_WRAP)).putString("iv",Base64.encodeToString(verified.getIV(),Base64.NO_WRAP)).commit())throw new Exception();result.success(user);}
                        else{byte[] raw=verified.doFinal(Base64.decode(prefs.getString("token",""),Base64.NO_WRAP));result.success(new JSONObject(new String(raw,StandardCharsets.UTF_8)));}}
                    catch(Exception e){disable();result.fail("أُلغيت بيانات البصمة؛ سجل الدخول بكلمة المرور مجددًا");}}
                @Override public void onAuthenticationError(int code,CharSequence message){result.fail(String.valueOf(message));}
            });
        }catch(Exception e){if(!enroll)disable();result.fail("البصمة غير متاحة؛ تحقق من إعدادات الجهاز أو استخدم كلمة المرور");}
    }
}
