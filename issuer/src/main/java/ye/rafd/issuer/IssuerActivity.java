package ye.rafd.issuer;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import org.json.JSONArray;
import android.text.TextWatcher;
import android.text.Editable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.*;

public final class IssuerActivity extends Activity {
    private static final int PICK=41, GREEN=0xff005e58, INK=0xff193b37, GOLD=0xffc99b34;
    private static final String EXPECTED="1198de2706055e9b6da3474f8784dc980f6e9d81b25d06f1b0c4f856574f69d4";
    private static final String FORMAT="rafd-license-key-v1";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile PrivateKey key;
    private byte[] bundle;
    private EditText password,device,entity,holder,code;
    private TextView state,fileStatus,date;
    private Spinner role,duration;
    private Button issue,copy,share,lock,unlock,changePassword,historyButton;
    private String parentPermit;
    private AlertDialog historyDialog;
    private static final int MAX_HISTORY=8*1024*1024;
    private boolean bootstrapPending;
    private int authGeneration;
    private final Calendar custom=Calendar.getInstance();
    private boolean customSet;
    private File keyFile(){return new File(getFilesDir(),"issuer-key.json");}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);root.setPadding(dp(16),dp(18),dp(16),dp(20));root.setBackgroundColor(0xfff5f8f7);scroll.addView(root);setContentView(scroll);
        TextView title=text("تصاريح رفد",27,GREEN);title.setTypeface(null,Typeface.BOLD);title.setGravity(Gravity.CENTER);root.addView(title);
        TextView sub=text("لوحة المطور • إصدار 1.0.2",14,INK);sub.setGravity(Gravity.CENTER);root.addView(sub);
        LinearLayout security=card(root);heading(security,"مفتاح الاستصدار");
        fileStatus=text("جارٍ التحقق من المفتاح المشفر",14,INK);security.addView(fileStatus);
        Button importKey=button(security,"استيراد مفتاح احتياطي",false,()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK);});
        password=field(security,"كلمة مرور المفتاح",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        unlock=button(security,"فتح المفتاح",true,()->unlockKey());
        changePassword=button(security,"تغيير كلمة المرور",false,()->changePasswordDialog());changePassword.setEnabled(false);
        lock=button(security,"قفل المفتاح",false,()->lockKey());lock.setEnabled(false);
        state=text("المفتاح مقفل",13,INK);security.addView(state);
        historyButton=button(root,"سجل التصاريح الصادرة",true,()->showHistory());historyButton.setEnabled(false);
        LinearLayout form=card(root);heading(form,"استصدار تصريح تشغيل");
        entity=field(form,"اسم الكيان أو المرفق (اختياري)",InputType.TYPE_CLASS_TEXT);
        holder=field(form,"اسم صاحب الجوال (اختياري)",InputType.TYPE_CLASS_TEXT);
        device=field(form,"معرّف الجهاز RFD",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);device.setTextDirection(View.TEXT_DIRECTION_LTR);
        button(form,"لصق معرّف الجهاز",false,()->{ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(cm.hasPrimaryClip())device.setText(cm.getPrimaryClip().getItemAt(0).coerceToText(this));});
        role=spinner(form,"نوع الجهاز",new String[]{"الجهاز الأم","موزع"});
        duration=spinner(form,"مدة التصريح",new String[]{"شهر","ستة أشهر","سنة","دائم","مدة مخصصة"});duration.setSelection(3);
        date=text("اختيار تاريخ الانتهاء",14,GREEN);date.setPadding(dp(8),dp(10),dp(8),dp(10));date.setVisibility(View.GONE);form.addView(date);
        date.setOnClickListener(v->{DatePickerDialog d=new DatePickerDialog(this,(a,y,mo,da)->{custom.set(y,mo,da,23,59,59);customSet=true;date.setText(String.format(Locale.ROOT,"%04d-%02d-%02d",y,mo+1,da));},custom.get(Calendar.YEAR),custom.get(Calendar.MONTH),custom.get(Calendar.DAY_OF_MONTH));d.getDatePicker().setMinDate(System.currentTimeMillis());d.show();});
        duration.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> a){}public void onItemSelected(AdapterView<?> a,View v,int i,long l){date.setVisibility(i==4?View.VISIBLE:View.GONE);}});
        issue=button(form,"استصدار التصريح",true,()->issueCode());issue.setEnabled(false);
        button(form,"تصريح جديد / تفريغ الحقول",false,()->clearForm());
        LinearLayout output=card(root);heading(output,"كود التصريح");
        code=new EditText(this);code.setHint("يظهر الكود هنا بعد الاستصدار");code.setTextColor(INK);code.setTextSize(12);code.setTextDirection(View.TEXT_DIRECTION_LTR);code.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);code.setMinLines(3);code.setMaxLines(6);code.setKeyListener(null);code.setTextIsSelectable(true);output.addView(code,new LinearLayout.LayoutParams(-1,-2));
        copy=button(output,"نسخ الكود",true,()->{String c=code.getText().toString();if(c.isEmpty())return;ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("تصريح رفد",c));toast("تم نسخ الكود");});copy.setEnabled(false);
        share=button(output,"مشاركة الكود",false,()->{String c=code.getText().toString();if(c.isEmpty())return;Intent i=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,c);startActivity(Intent.createChooser(i,"مشاركة تصريح رفد"));});share.setEnabled(false);
        TextView help=text("لا ترسل ملف المفتاح أو كلمة مروره للمستخدمين. أرسل كود التصريح فقط.",12,INK);root.addView(help);
        try{if(keyFile().isFile()){bundle=readLimited(new FileInputStream(keyFile()));validateBundle(bundle);fileStatus.setText("المفتاح المشفر محفوظ على الجهاز.");}else{bundle=readLimited(getAssets().open("owner/permit-key.json"));validateBundle(bundle);bootstrapPending=true;fileStatus.setText("المفتاح مضمّن وجاهز. افتحه بكلمة المرور السابقة، ثم اختر كلمة مرور جديدة.");}importKey.setVisibility(View.GONE);}catch(Exception e){bundle=null;fileStatus.setText("تعذر تحميل المفتاح. اختر ملف المفتاح الاحتياطي.");}
    }
    private TextView text(String t,int size,int color){TextView v=new TextView(this);v.setText(t);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(5),0,dp(5));return v;}
    private LinearLayout card(LinearLayout parent){LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(12),dp(9),dp(12),dp(10));GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(12));bg.setStroke(dp(1),0xffdce7e3);p.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(12);parent.addView(p,lp);return p;}
    private void heading(LinearLayout p,String s){TextView v=text(s,17,GREEN);v.setTypeface(null,Typeface.BOLD);p.addView(v);}
    private EditText field(LinearLayout p,String hint,int type){EditText e=new EditText(this);e.setHint(hint);e.setTextSize(14);e.setInputType(type);e.setSingleLine(true);e.setPadding(dp(10),dp(8),dp(10),dp(8));e.setMinHeight(dp(44));GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(8));bg.setStroke(dp(1),0xffb7cdc6);e.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(5);p.addView(e,lp);return e;}
    private Button button(LinearLayout p,String s,boolean primary,Runnable action){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(primary?Color.WHITE:GREEN);GradientDrawable bg=new GradientDrawable();bg.setColor(primary?GREEN:0xffeaf3f0);bg.setCornerRadius(dp(8));b.setBackground(bg);b.setMinHeight(dp(44));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(6);p.addView(b,lp);b.setOnClickListener(v->action.run());return b;}
    private Spinner spinner(LinearLayout p,String label,String[] choices){p.addView(text(label,12,INK));Spinner s=new Spinner(this);ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,choices);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);s.setMinimumHeight(dp(42));p.addView(s,new LinearLayout.LayoutParams(-1,-2));return s;}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private static byte[] readLimited(InputStream in)throws Exception{try(InputStream i=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=i.read(b))!=-1){if(out.size()+n>65536)throw new IOException("Too large");out.write(b,0,n);}return out.toByteArray();}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=PICK||result!=RESULT_OK||data==null||data.getData()==null)return;try{byte[] candidate=readLimited(getContentResolver().openInputStream(data.getData()));validateBundle(candidate);lockKey();bundle=candidate;bootstrapPending=true;fileStatus.setText("تم اختيار المفتاح. افتحه ثم اختر كلمة مرور خاصة بك.");}catch(Exception e){toast("ملف المفتاح غير صالح لرفد");}}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
    private static JSONObject validateBundle(byte[] raw)throws Exception{JSONObject j=new JSONObject(new String(raw,StandardCharsets.UTF_8));if(!FORMAT.equals(j.getString("format"))||j.getInt("iterations")!=310000||!EXPECTED.equals(hex(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(j.getString("publicKey"))))))throw new SecurityException("Wrong key");return j;}
    private static PrivateKey decrypt(byte[] raw,char[] pass)throws Exception{JSONObject j=validateBundle(raw);Base64.Decoder b=Base64.getDecoder();PBEKeySpec spec=new PBEKeySpec(pass,b.decode(j.getString("salt")),310000,256);byte[] derived=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();spec.clearPassword();byte[] pk=null;try{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(derived,"AES"),new GCMParameterSpec(128,b.decode(j.getString("iv"))));c.updateAAD(FORMAT.getBytes(StandardCharsets.UTF_8));pk=c.doFinal(b.decode(j.getString("encrypted")));KeyFactory f=KeyFactory.getInstance("RSA");PrivateKey k=f.generatePrivate(new PKCS8EncodedKeySpec(pk));PublicKey pub=f.generatePublic(new X509EncodedKeySpec(b.decode(j.getString("publicKey"))));byte[] probe="rafd-check".getBytes(StandardCharsets.UTF_8);Signature sig=Signature.getInstance("SHA256withRSA");sig.initSign(k);sig.update(probe);byte[] signature=sig.sign();sig.initVerify(pub);sig.update(probe);if(!sig.verify(signature))throw new SecurityException("Mismatch");return k;}finally{Arrays.fill(derived,(byte)0);if(pk!=null)Arrays.fill(pk,(byte)0);}}
    private void saveBundle(byte[] data)throws Exception{
        android.util.AtomicFile atomic=new android.util.AtomicFile(keyFile());FileOutputStream out=null;
        try{out=atomic.startWrite();out.write(data);atomic.finishWrite(out);}catch(Exception e){if(out!=null)atomic.failWrite(out);throw e;}
    }
    private static byte[] reencrypt(byte[] old,PrivateKey key,char[] pass)throws Exception{
        JSONObject prior=validateBundle(old);SecureRandom random=new SecureRandom();byte[] salt=new byte[16],iv=new byte[12];random.nextBytes(salt);random.nextBytes(iv);
        PBEKeySpec spec=new PBEKeySpec(pass,salt,310000,256);byte[] derived=null,plain=null;
        try{derived=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();plain=key.getEncoded();
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(derived,"AES"),new GCMParameterSpec(128,iv));c.updateAAD(FORMAT.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder b=Base64.getEncoder();byte[] encrypted=c.doFinal(plain);
            JSONObject j=new JSONObject().put("format",FORMAT).put("iterations",310000).put("salt",b.encodeToString(salt)).put("iv",b.encodeToString(iv)).put("encrypted",b.encodeToString(encrypted)).put("publicKey",prior.getString("publicKey"));
            byte[] result=j.toString().getBytes(StandardCharsets.UTF_8);decrypt(result,pass);return result;
        }finally{spec.clearPassword();if(derived!=null)Arrays.fill(derived,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
    private void unlockKey(){
        if(bundle==null){toast("لا يوجد مفتاح صالح");return;}
        char[] pass=password.getText().toString().trim().toCharArray();password.setText("");
        if(pass.length==0){toast("أدخل كلمة المرور");return;}
        byte[] selected=bundle.clone();int generation=++authGeneration;unlock.setEnabled(false);state.setText("جارٍ التحقق من المفتاح");
        worker.execute(()->{try{PrivateKey k=decrypt(selected,pass);runOnUiThread(()->{
            if(isFinishing()||isDestroyed()||generation!=authGeneration)return;key=k;issue.setEnabled(!bootstrapPending);historyButton.setEnabled(!bootstrapPending);lock.setEnabled(true);unlock.setEnabled(true);changePassword.setEnabled(true);
            state.setText(bootstrapPending?"اختر كلمة مرور جديدة لاستكمال التهيئة":"المفتاح مفتوح ومطابق");
            if(bootstrapPending)changePasswordDialog();
        });}catch(Exception e){runOnUiThread(()->{if(isFinishing()||isDestroyed()||generation!=authGeneration)return;unlock.setEnabled(true);state.setText("تعذر فتح المفتاح؛ تحقق من كلمة المرور");});}finally{Arrays.fill(pass,'\0');}});
    }
    private void changePasswordDialog(){
        PrivateKey k=key;if(k==null||bundle==null){toast("افتح المفتاح أولًا");return;}
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);layout.setPadding(dp(16),dp(8),dp(16),dp(8));
        EditText next=field(layout,"كلمة المرور الجديدة (8 أحرف فأكثر)",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText again=field(layout,"تأكيد كلمة المرور الجديدة",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("تعيين كلمة المرور").setMessage("احتفظ بكلمة المرور في مكان آمن. لن تتغير أكواد التصاريح السابقة.").setView(layout).setNegativeButton("إلغاء",null).setPositiveButton("حفظ",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String a=next.getText().toString().trim(),b=again.getText().toString().trim();
            if(a.length()<8){next.setError("ثمانية أحرف على الأقل");return;}if(!a.equals(b)){again.setError("كلمتا المرور غير متطابقتين");return;}
            if(key!=k){d.dismiss();toast("افتح المفتاح مجددًا");return;}
            char[] pass=a.toCharArray();next.setText("");again.setText("");byte[] selected=bundle.clone();int generation=++authGeneration;d.dismiss();issue.setEnabled(false);historyButton.setEnabled(false);unlock.setEnabled(false);lock.setEnabled(false);changePassword.setEnabled(false);state.setText("جارٍ تشفير المفتاح بكلمة المرور الجديدة");
            worker.execute(()->{try{byte[] updated=reencrypt(selected,k,pass);saveBundle(updated);runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;bundle=updated;bootstrapPending=false;lockKey();fileStatus.setText("المفتاح محفوظ بكلمة مرورك الجديدة.");state.setText("تم تغيير كلمة المرور. افتح المفتاح بكلمة المرور الجديدة.");toast("تم حفظ كلمة المرور الجديدة");
            });}catch(Exception e){runOnUiThread(()->{if(isFinishing()||isDestroyed()||generation!=authGeneration)return;lockKey();state.setText("تعذر حفظ كلمة المرور؛ لم يتغير المفتاح السابق");});}finally{Arrays.fill(pass,'\0');}});
        }));d.show();
    }
    private void lockKey(){authGeneration++;key=null;password.setText("");issue.setEnabled(false);historyButton.setEnabled(false);lock.setEnabled(false);changePassword.setEnabled(false);unlock.setEnabled(true);if(historyDialog!=null){historyDialog.dismiss();historyDialog=null;}code.setText("");copy.setEnabled(false);share.setEnabled(false);state.setText("المفتاح مقفل");}
    private static String b64(byte[] b){return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private void issueCode(){try{PrivateKey k=key;if(k==null||bootstrapPending)throw new IllegalStateException("المفتاح مقفل");String id=device.getText().toString().trim().toUpperCase(Locale.ROOT).replaceFirst("^RFD[-\\s]*","").replaceAll("[\\s-]","");if(!id.matches("[0-9A-F]{32}"))throw new IllegalArgumentException("معرّف الجهاز غير صحيح");long now=System.currentTimeMillis()/1000L,exp=0;int d=duration.getSelectedItemPosition();if(d<3)exp=now+new long[]{30,180,365}[d]*86400L;else if(d==4){if(!customSet)throw new IllegalArgumentException("حدد تاريخ الانتهاء");exp=custom.getTimeInMillis()/1000L;if(exp<=now)throw new IllegalArgumentException("تاريخ الانتهاء غير صالح");}String e=entity.getText().toString().trim(),h=holder.getText().toString().trim();if(e.length()>120||h.length()>120)throw new IllegalArgumentException("الاسم طويل جدًا");JSONObject p=new JSONObject().put("v",1).put("app","rafd").put("device",id).put("role",role.getSelectedItemPosition()==0?"mother":"distributor").put("iat",now).put("exp",exp).put("id",UUID.randomUUID().toString()).put("entity",e).put("holder",h);String prefix="RFD1."+b64(p.toString().getBytes(StandardCharsets.UTF_8));Signature sig=Signature.getInstance("SHA256withRSA");sig.initSign(k);sig.update(prefix.getBytes(StandardCharsets.US_ASCII));String token=prefix+"."+b64(sig.sign());saveHistory(p,token,parentPermit);parentPermit=null;issue.setText("استصدار التصريح");code.setText(token);copy.setEnabled(true);share.setEnabled(true);toast("تم استصدار التصريح وحفظه في السجل");}catch(Exception e){toast(e instanceof IllegalArgumentException?e.getMessage():"تعذر استصدار التصريح");}}
    private void clearForm(){parentPermit=null;entity.setText("");holder.setText("");device.setText("");role.setSelection(0);duration.setSelection(3);customSet=false;date.setText("اختيار تاريخ الانتهاء");issue.setText("استصدار التصريح");code.setText("");copy.setEnabled(false);share.setEnabled(false);}
    private File historyFile(){return new File(getFilesDir(),"issued-permits-v1.enc");}
    private javax.crypto.SecretKey historyKey(boolean create)throws Exception{
        java.security.KeyStore ks=java.security.KeyStore.getInstance("AndroidKeyStore");ks.load(null);String alias="rafd-issued-permits-v1";
        if(!ks.containsAlias(alias)){
            if(!create||historyFile().exists())throw new SecurityException("History key missing");
            javax.crypto.KeyGenerator gen=javax.crypto.KeyGenerator.getInstance("AES","AndroidKeyStore");
            gen.init(new android.security.keystore.KeyGenParameterSpec.Builder(alias,android.security.keystore.KeyProperties.PURPOSE_ENCRYPT|android.security.keystore.KeyProperties.PURPOSE_DECRYPT).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());gen.generateKey();
        }
        return (javax.crypto.SecretKey)ks.getKey(alias,null);
    }
    private JSONArray loadHistory()throws Exception{
        android.util.AtomicFile f=new android.util.AtomicFile(historyFile());
        if(!historyFile().exists()&&!new File(historyFile().getPath()+".bak").exists())return new JSONArray();
        try(InputStream in=f.openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>MAX_HISTORY+64)throw new IOException("History too large");out.write(b,0,n);}
            byte[] raw=out.toByteArray();if(raw.length<29||raw[0]!=1)throw new IOException("Invalid history");
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,historyKey(false),new GCMParameterSpec(128,Arrays.copyOfRange(raw,1,13)));c.updateAAD("rafd-issuer-history-v1".getBytes(StandardCharsets.UTF_8));return new JSONArray(new String(c.doFinal(Arrays.copyOfRange(raw,13,raw.length)),StandardCharsets.UTF_8));
        }
    }
    private void writeHistory(JSONArray records)throws Exception{
        byte[] plain=records.toString().getBytes(StandardCharsets.UTF_8);if(plain.length>MAX_HISTORY)throw new IOException("History too large");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,historyKey(true));c.updateAAD("rafd-issuer-history-v1".getBytes(StandardCharsets.UTF_8));byte[] iv=c.getIV(),encrypted=c.doFinal(plain);if(iv.length!=12)throw new IOException("Invalid IV");
        android.util.AtomicFile f=new android.util.AtomicFile(historyFile());FileOutputStream out=null;try{out=f.startWrite();out.write(1);out.write(iv);out.write(encrypted);f.finishWrite(out);}catch(Exception e){if(out!=null)f.failWrite(out);throw e;}
    }
    private void saveHistory(JSONObject p,String token,String parent)throws Exception{
        if(key==null||bootstrapPending)throw new SecurityException("Locked");
        JSONArray records=loadHistory();for(int i=0;i<records.length();i++)if(p.getString("id").equals(records.getJSONObject(i).getString("id")))return;
        JSONObject row=new JSONObject(p.toString()).put("token",token).put("recordedAt",System.currentTimeMillis()/1000L);if(parent!=null)row.put("parent",parent);
        records.put(row);writeHistory(records);
    }
    private JSONObject verifyOldCode(String raw)throws Exception{
        String token=raw.replaceAll("\\s+","");if(token.length()>8192)throw new SecurityException("Invalid token");String[] a=token.split("\\.",-1);if(a.length!=3||!"RFD1".equals(a[0]))throw new SecurityException("Invalid token");
        JSONObject j=validateBundle(bundle);PublicKey pub=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(j.getString("publicKey"))));
        Signature sig=Signature.getInstance("SHA256withRSA");sig.initVerify(pub);sig.update((a[0]+"."+a[1]).getBytes(StandardCharsets.US_ASCII));if(!sig.verify(Base64.getUrlDecoder().decode(a[2])))throw new SecurityException("Wrong signature");
        JSONObject p=new JSONObject(new String(Base64.getUrlDecoder().decode(a[1]),StandardCharsets.UTF_8));
        if(p.getInt("v")!=1||!"rafd".equals(p.getString("app"))||!p.getString("device").matches("[0-9A-F]{32}")||!Arrays.asList("mother","distributor").contains(p.getString("role")))throw new SecurityException("Wrong payload");
        UUID.fromString(p.getString("id"));p.getLong("iat");p.getLong("exp");return p;
    }
    private String fmt(long seconds){return seconds==0?"دائم":new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date(seconds*1000L));}
    private String roleName(JSONObject p){return "mother".equals(p.optString("role"))?"الجهاز الأم":"موزع";}
    private String statusName(JSONObject p){long exp=p.optLong("exp");return exp==0||exp>System.currentTimeMillis()/1000L?"ساري بحسب تاريخه":"منتهي";}
    private void copyToken(String token){ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("تصريح رفد",token));toast("تم نسخ الكود");}
    private void shareToken(String token){Intent i=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,token);startActivity(Intent.createChooser(i,"مشاركة تصريح رفد"));}
    private void importOld(){
        if(key==null||bootstrapPending)return;
        EditText e=new EditText(this);e.setHint("ألصق كود التصريح الصادر سابقًا");e.setTextDirection(View.TEXT_DIRECTION_LTR);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);e.setMinLines(3);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("إضافة تصريح سابق").setView(e).setNegativeButton("إلغاء",null).setPositiveButton("حفظ",null).create();
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{try{String token=e.getText().toString().replaceAll("\\s+","");JSONObject p=verifyOldCode(token);saveHistory(p,token,null);d.dismiss();toast("تم حفظ التصريح في السجل");showHistory();}catch(Exception x){e.setError("الكود غير صالح أو تعذر حفظه");}}));d.show();historyDialog=d;
    }
    private void prepareReissue(JSONObject p){
        if(key==null||bootstrapPending)return;
        if(historyDialog!=null)historyDialog.dismiss();clearForm();parentPermit=p.optString("id");entity.setText(p.optString("entity"));holder.setText(p.optString("holder"));device.setText("RFD-"+p.optString("device"));role.setSelection("mother".equals(p.optString("role"))?0:1);
        long exp=p.optLong("exp");if(exp==0)duration.setSelection(3);else if(exp>System.currentTimeMillis()/1000L){custom.setTimeInMillis(exp*1000L);customSet=true;date.setText(fmt(exp));duration.setSelection(4);}else{duration.setSelection(0);}
        issue.setText("حفظ التعديل وإصدار كود جديد");device.requestFocus();toast("عدّل البيانات أو المدة ثم أصدر كودًا جديدًا");
    }
    private void showDetails(JSONObject p){
        if(key==null||bootstrapPending)return;if(historyDialog!=null)historyDialog.dismiss();
        String token=p.optString("token");LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);l.setPadding(dp(14),dp(8),dp(14),dp(10));
        l.addView(text("الكيان: "+p.optString("entity","")+"\nصاحب الجوال: "+p.optString("holder","")+"\nنوع الجهاز: "+roleName(p)+"\nمعرّف الجهاز: RFD-"+p.optString("device")+"\nتاريخ الإصدار: "+fmt(p.optLong("iat"))+"\nالانتهاء: "+fmt(p.optLong("exp"))+"\nالحالة: "+statusName(p),14,INK));
        button(l,"نسخ الكود",true,()->copyToken(token));button(l,"مشاركة الكود",false,()->shareToken(token));button(l,"تعديل / تجديد التصريح",false,()->prepareReissue(p));
        l.addView(text("التعديل يصدر كودًا جديدًا. لا يلغي الكود السابق عن بُعد.",12,INK));
        historyDialog=new AlertDialog.Builder(this).setTitle("تفاصيل التصريح").setView(l).setNegativeButton("إغلاق",null).setPositiveButton("رجوع للسجل",(d,w)->showHistory()).create();historyDialog.show();
    }
    private void showHistory(){
        if(key==null||bootstrapPending){toast("افتح المفتاح أولًا");return;}
        try{if(historyDialog!=null)historyDialog.dismiss();JSONArray records=loadHistory();LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);l.setPadding(dp(12),dp(6),dp(12),dp(10));
            EditText search=field(l,"بحث بالاسم أو الكيان أو معرّف الجهاز",InputType.TYPE_CLASS_TEXT);TextView count=text("",12,INK);l.addView(count);
            button(l,"إضافة كود صادر سابقًا",false,()->{if(historyDialog!=null)historyDialog.dismiss();importOld();});
            ScrollView sc=new ScrollView(this);LinearLayout rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);sc.addView(rows);l.addView(sc,new LinearLayout.LayoutParams(-1,dp(340)));
            Runnable render=()->{rows.removeAllViews();String q=search.getText().toString().trim().toLowerCase(Locale.ROOT);int matches=0;Set<String> parents=new HashSet<>();for(int i=0;i<records.length();i++){JSONObject p=records.optJSONObject(i);if(p!=null&&p.has("parent"))parents.add(p.optString("parent"));}
                for(int i=records.length()-1;i>=0;i--){JSONObject p=records.optJSONObject(i);if(p==null)continue;String all=(p.optString("entity")+" "+p.optString("holder")+" "+p.optString("device")+" "+roleName(p)).toLowerCase(Locale.ROOT);if(!all.contains(q)&&!all.replace("-","").contains(q.replace("-","").replace("rfd","")))continue;matches++;if(matches>100)continue;
                String title=p.optString("holder");if(title.isEmpty())title=p.optString("entity");if(title.isEmpty())title="RFD-"+p.optString("device");
                String note=parents.contains(p.optString("id"))?" • أُعيد إصداره":"";
                Button b=button(rows,title+"\n"+roleName(p)+" • "+fmt(p.optLong("exp"))+" • "+statusName(p)+note,false,()->showDetails(p));b.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);}
                count.setText("التصاريح: "+matches+(matches>100?" — تظهر أحدث 100 نتيجة؛ ضيّق البحث":""));if(matches==0)rows.addView(text("لا توجد تصاريح مطابقة",14,INK));};
            search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence a,int st,int c,int af){}public void onTextChanged(CharSequence a,int st,int before,int c){render.run();}public void afterTextChanged(Editable e){}});render.run();
            historyDialog=new AlertDialog.Builder(this).setTitle("سجل التصاريح الصادرة").setView(l).setPositiveButton("إغلاق",null).create();historyDialog.show();
        }catch(Exception e){toast("تعذر قراءة السجل؛ لم تُحذف بياناته");}
    }
    @Override protected void onStop(){if(key!=null&&unlock.isEnabled())lockKey();super.onStop();}
    @Override protected void onDestroy(){authGeneration++;key=null;worker.shutdownNow();super.onDestroy();}
}
