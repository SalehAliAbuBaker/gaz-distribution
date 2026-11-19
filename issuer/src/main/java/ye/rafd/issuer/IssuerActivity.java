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
    private Button issue,copy,share,lock,unlock;
    private final Calendar custom=Calendar.getInstance();
    private boolean customSet;
    private File keyFile(){return new File(getFilesDir(),"issuer-key.json");}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);root.setPadding(dp(16),dp(18),dp(16),dp(20));root.setBackgroundColor(0xfff5f8f7);scroll.addView(root);setContentView(scroll);
        TextView title=text("تصاريح رفد",27,GREEN);title.setTypeface(null,Typeface.BOLD);title.setGravity(Gravity.CENTER);root.addView(title);
        TextView sub=text("لوحة المطور • إصدار 1.0.0",14,INK);sub.setGravity(Gravity.CENTER);root.addView(sub);
        LinearLayout security=card(root);heading(security,"مفتاح الاستصدار");
        fileStatus=text("استورد ملف المفتاح المشفر مرة واحدة.",14,INK);security.addView(fileStatus);
        button(security,"استيراد ملف المفتاح",false,()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK);});
        password=field(security,"كلمة مرور المفتاح",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        unlock=button(security,"فتح المفتاح",true,()->unlockKey());
        lock=button(security,"قفل المفتاح",false,()->lockKey());lock.setEnabled(false);
        state=text("المفتاح مقفل",13,INK);security.addView(state);
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
        LinearLayout output=card(root);heading(output,"كود التصريح");
        code=new EditText(this);code.setHint("يظهر الكود هنا بعد الاستصدار");code.setTextColor(INK);code.setTextSize(12);code.setTextDirection(View.TEXT_DIRECTION_LTR);code.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);code.setMinLines(3);code.setMaxLines(6);code.setKeyListener(null);code.setTextIsSelectable(true);output.addView(code,new LinearLayout.LayoutParams(-1,-2));
        copy=button(output,"نسخ الكود",true,()->{String c=code.getText().toString();if(c.isEmpty())return;ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("تصريح رفد",c));toast("تم نسخ الكود");});copy.setEnabled(false);
        share=button(output,"مشاركة الكود",false,()->{String c=code.getText().toString();if(c.isEmpty())return;Intent i=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,c);startActivity(Intent.createChooser(i,"مشاركة تصريح رفد"));});share.setEnabled(false);
        TextView help=text("لا ترسل ملف المفتاح أو كلمة مروره للمستخدمين. أرسل كود التصريح فقط.",12,INK);root.addView(help);
        try{if(keyFile().isFile()){bundle=readLimited(new FileInputStream(keyFile()));fileStatus.setText("ملف المفتاح المشفر محفوظ على الجهاز.");}}catch(Exception e){bundle=null;}
    }
    private TextView text(String t,int size,int color){TextView v=new TextView(this);v.setText(t);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(5),0,dp(5));return v;}
    private LinearLayout card(LinearLayout parent){LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(12),dp(9),dp(12),dp(10));GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(12));bg.setStroke(dp(1),0xffdce7e3);p.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(12);parent.addView(p,lp);return p;}
    private void heading(LinearLayout p,String s){TextView v=text(s,17,GREEN);v.setTypeface(null,Typeface.BOLD);p.addView(v);}
    private EditText field(LinearLayout p,String hint,int type){EditText e=new EditText(this);e.setHint(hint);e.setTextSize(14);e.setInputType(type);e.setSingleLine(true);e.setPadding(dp(10),dp(8),dp(10),dp(8));e.setMinHeight(dp(44));GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(8));bg.setStroke(dp(1),0xffb7cdc6);e.setBackground(bg);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(5);p.addView(e,lp);return e;}
    private Button button(LinearLayout p,String s,boolean primary,Runnable action){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(primary?Color.WHITE:GREEN);GradientDrawable bg=new GradientDrawable();bg.setColor(primary?GREEN:0xffeaf3f0);bg.setCornerRadius(dp(8));b.setBackground(bg);b.setMinHeight(dp(44));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(6);p.addView(b,lp);b.setOnClickListener(v->action.run());return b;}
    private Spinner spinner(LinearLayout p,String label,String[] choices){p.addView(text(label,12,INK));Spinner s=new Spinner(this);ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,choices);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);s.setMinimumHeight(dp(42));p.addView(s,new LinearLayout.LayoutParams(-1,-2));return s;}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private static byte[] readLimited(InputStream in)throws Exception{try(InputStream i=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=i.read(b))!=-1){if(out.size()+n>65536)throw new IOException("Too large");out.write(b,0,n);}return out.toByteArray();}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=PICK||result!=RESULT_OK||data==null||data.getData()==null)return;try{byte[] candidate=readLimited(getContentResolver().openInputStream(data.getData()));validateBundle(candidate);lockKey();bundle=candidate;fileStatus.setText("تم اختيار المفتاح. أدخل كلمة المرور لفتحه وحفظه مشفرًا.");}catch(Exception e){toast("ملف المفتاح غير صالح لرفد");}}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
    private static JSONObject validateBundle(byte[] raw)throws Exception{JSONObject j=new JSONObject(new String(raw,StandardCharsets.UTF_8));if(!FORMAT.equals(j.getString("format"))||j.getInt("iterations")!=310000||!EXPECTED.equals(hex(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(j.getString("publicKey"))))))throw new SecurityException("Wrong key");return j;}
    private static PrivateKey decrypt(byte[] raw,char[] pass)throws Exception{JSONObject j=validateBundle(raw);Base64.Decoder b=Base64.getDecoder();PBEKeySpec spec=new PBEKeySpec(pass,b.decode(j.getString("salt")),310000,256);byte[] derived=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();spec.clearPassword();byte[] pk=null;try{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(derived,"AES"),new GCMParameterSpec(128,b.decode(j.getString("iv"))));c.updateAAD(FORMAT.getBytes(StandardCharsets.UTF_8));pk=c.doFinal(b.decode(j.getString("encrypted")));KeyFactory f=KeyFactory.getInstance("RSA");PrivateKey k=f.generatePrivate(new PKCS8EncodedKeySpec(pk));PublicKey pub=f.generatePublic(new X509EncodedKeySpec(b.decode(j.getString("publicKey"))));byte[] probe="rafd-check".getBytes(StandardCharsets.UTF_8);Signature sig=Signature.getInstance("SHA256withRSA");sig.initSign(k);sig.update(probe);byte[] signature=sig.sign();sig.initVerify(pub);sig.update(probe);if(!sig.verify(signature))throw new SecurityException("Mismatch");return k;}finally{Arrays.fill(derived,(byte)0);if(pk!=null)Arrays.fill(pk,(byte)0);}}
    private void unlockKey(){if(bundle==null){toast("استورد ملف المفتاح أولًا");return;}char[] pass=password.getText().toString().trim().toCharArray();password.setText("");if(pass.length==0){toast("أدخل كلمة المرور");return;}byte[] selected=bundle.clone();unlock.setEnabled(false);state.setText("جارٍ التحقق من المفتاح");worker.execute(()->{try{PrivateKey k=decrypt(selected,pass);try(FileOutputStream out=openFileOutput("issuer-key.json",MODE_PRIVATE)){out.write(selected);}runOnUiThread(()->{if(isFinishing()||isDestroyed())return;key=k;issue.setEnabled(true);lock.setEnabled(true);unlock.setEnabled(true);state.setText("المفتاح مفتوح ومطابق");fileStatus.setText("ملف المفتاح المشفر محفوظ على الجهاز.");});}catch(Exception e){runOnUiThread(()->{if(isFinishing()||isDestroyed())return;unlock.setEnabled(true);state.setText("تعذر فتح المفتاح؛ تحقق من كلمة المرور");});}finally{Arrays.fill(pass,'\0');}});}
    private void lockKey(){key=null;password.setText("");issue.setEnabled(false);lock.setEnabled(false);state.setText("المفتاح مقفل");}
    private static String b64(byte[] b){return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    private void issueCode(){try{PrivateKey k=key;if(k==null)throw new IllegalStateException("المفتاح مقفل");String id=device.getText().toString().trim().toUpperCase(Locale.ROOT).replaceFirst("^RFD[-\\s]*","").replaceAll("[\\s-]","");if(!id.matches("[0-9A-F]{32}"))throw new IllegalArgumentException("معرّف الجهاز غير صحيح");long now=System.currentTimeMillis()/1000L,exp=0;int d=duration.getSelectedItemPosition();if(d<3)exp=now+new long[]{30,180,365}[d]*86400L;else if(d==4){if(!customSet)throw new IllegalArgumentException("حدد تاريخ الانتهاء");exp=custom.getTimeInMillis()/1000L;if(exp<=now)throw new IllegalArgumentException("تاريخ الانتهاء غير صالح");}String e=entity.getText().toString().trim(),h=holder.getText().toString().trim();if(e.length()>120||h.length()>120)throw new IllegalArgumentException("الاسم طويل جدًا");JSONObject p=new JSONObject().put("v",1).put("app","rafd").put("device",id).put("role",role.getSelectedItemPosition()==0?"mother":"distributor").put("iat",now).put("exp",exp).put("id",UUID.randomUUID().toString()).put("entity",e).put("holder",h);String prefix="RFD1."+b64(p.toString().getBytes(StandardCharsets.UTF_8));Signature sig=Signature.getInstance("SHA256withRSA");sig.initSign(k);sig.update(prefix.getBytes(StandardCharsets.US_ASCII));code.setText(prefix+"."+b64(sig.sign()));copy.setEnabled(true);share.setEnabled(true);toast("تم استصدار التصريح");}catch(Exception e){toast(e instanceof IllegalArgumentException?e.getMessage():"تعذر استصدار التصريح");}}
    @Override protected void onDestroy(){key=null;worker.shutdownNow();super.onDestroy();}
}
