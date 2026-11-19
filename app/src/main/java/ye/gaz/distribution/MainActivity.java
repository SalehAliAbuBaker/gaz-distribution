package ye.gaz.distribution;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.webkit.*;
import android.widget.*;
import android.text.InputType;
import android.print.*;
import android.graphics.pdf.PdfRenderer;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;
import java.io.*;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.*;
import android.util.Base64;
import org.json.*;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import android.graphics.Bitmap;
import android.graphics.Color;

public final class MainActivity extends Activity {
    private static final String HOME="https://appassets.androidplatform.net/assets/index.html";
    private static final int SAVE=21,RESTORE=22,IMPORT_EXCEL=23;
    private WebView web;
    private RafdLicense license;
    private RafdPermit activePermit;
    private final Handler permitHandler=new Handler(Looper.getMainLooper());
    private final Runnable permitTick=new Runnable(){public void run(){if(web!=null&&!isFinishing()){if(!permitValid()){closeLan();recreate();return;}permitHandler.postDelayed(this,30000L);}}};
    private GazStore store;
    private File pending;
    private String restoreAdmin,excelAdmin;
    private LocalBiometric biometric;
    private final ExecutorService workers=Executors.newSingleThreadExecutor();
    private String device;
    private GazPairing pairing;
    private String scanMode, scanAdmin;
    private GazLan lan;
    private volatile String distributorSessionId;
    private long lastLoginAttempt;
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);biometric=new LocalBiometric(this);if(saved!=null)excelAdmin=saved.getString("excelAdmin");
        try{store=new GazStore(this);store.readState();}catch(Exception e){new AlertDialog.Builder(this).setTitle("تعذر فتح البيانات").setMessage("لم تُحذف بياناتك. راجع الدعم الفني.").setPositiveButton("إغلاق",(d,w)->finish()).setCancelable(false).show();return;}
        try{license=new RafdLicense(this);}catch(Exception e){new AlertDialog.Builder(this).setTitle("تعذر تحديد هوية الجهاز").setMessage("لم تتغير بياناتك. راجع المطور.").setPositiveButton("إغلاق",(d,w)->finish()).setCancelable(false).show();return;}
        if(!permitValid()){activationScreen();return;}
        startLicensedApp(saved);
    }
    private boolean permitValid(){try{String role=store.role();activePermit=license.verify(role);if("independent".equals(role)&&"distributor".equals(activePermit.role)){String raw=store.readState();if(raw!=null&&!"null".equals(raw)){JSONObject state=new JSONObject(raw);for(String k:new String[]{"families","cycles","deliveries"})if(state.getJSONArray(k).length()!=0)throw new SecurityException("Independent device holds records");}}return true;}catch(Exception e){activePermit=null;return false;}}
    private boolean licensedRole(String role){return permitValid()&&activePermit.role.equals(role);}
    private boolean distributorSetupOnly(){return activePermit!=null&&"distributor".equals(activePermit.role)&&!isDistributor();}
    private String displayPermitDevice(){return RafdPermit.displayCode(license.code);}
    private void copyPermitDevice(){android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("Rafd device",displayPermitDevice()));msg("تم نسخ معرّف الجهاز");}
    private void sharePermitDevice(){Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,"طلب تصريح تشغيل رفد\\nمعرّف الجهاز: "+displayPermitDevice());startActivity(Intent.createChooser(i,"إرسال معرّف الجهاز"));}
    private void activationScreen(){
        LinearLayout p=pairLayout();ScrollView sc=new ScrollView(this);sc.setFillViewport(true);sc.addView(p);p.setBackgroundColor(Color.WHITE);
        TextView title=new TextView(this);title.setText("رفد");title.setTextSize(32);title.setTextColor(Color.rgb(0,94,88));title.setGravity(android.view.Gravity.CENTER);p.addView(title);
        pairText(p,"لإدارة المساعدات وتنظيمها");pairText(p,"تفعيل تصريح التشغيل — الإصدار 1.0.3");
        pairText(p,"أرسل معرّف الجهاز إلى المطور لطلب التصريح. تظل البيانات محفوظة حتى عند انتهاء التصريح.");
        TextView code=new TextView(this);code.setText(displayPermitDevice());code.setTextIsSelectable(true);code.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);code.setTextSize(17);code.setGravity(android.view.Gravity.CENTER);p.addView(code);
        pairButton(p,"نسخ معرّف الجهاز",()->copyPermitDevice());
        pairButton(p,"مشاركة معرّف الجهاز",()->sharePermitDevice());
        EditText token=new EditText(this);token.setHint("ألصق كود التصريح هنا");token.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);token.setMaxLines(5);p.addView(token);
        pairButton(p,"تفعيل التطبيق",()->activatePermit(token));
        pairButton(p,"الخروج",()->confirmExit());setContentView(sc);
    }
    private void activatePermit(EditText token){
        try{license.install(token.getText().toString(),store.role());activePermit=license.verify(store.role());token.setText("");msg("تم تفعيل التصريح");recreate();}
        catch(Exception e){token.setError("التصريح غير صالح لهذا الجهاز أو لدوره، أو انتهت مدته. تحقق من التاريخ والكود.");}
    }
    private void showPermitInfo(){
        if(!permitValid()){recreate();return;}
        RafdPermit p=activePermit;LinearLayout layout=pairLayout();
        pairText(layout,"معرّف الجهاز: "+displayPermitDevice()+"\\nنوع التصريح: "+("mother".equals(p.role)?"الجهاز الأم":"موزع")+"\\nالانتهاء: "+(p.expiry==0?"دائم":new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.ROOT).format(new java.util.Date(p.expiry*1000L)))+(p.entity.isEmpty()?"":"\\nالكيان: "+p.entity)+(p.holder.isEmpty()?"":"\\nصاحب الجهاز: "+p.holder));
        pairButton(layout,"نسخ معرّف الجهاز",()->copyPermitDevice());pairButton(layout,"مشاركة معرّف الجهاز",()->sharePermitDevice());
        EditText token=new EditText(this);token.setHint("كود تصريح جديد (للتجديد)");token.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);token.setMaxLines(5);layout.addView(token);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("تصريح التشغيل").setView(layout).setNegativeButton("إغلاق",null).setPositiveButton("تجديد التصريح",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->activatePermit(token)));dialog.show();
    }
    private void startLicensedApp(Bundle saved){
        android.content.SharedPreferences prefs=getSharedPreferences("identity",MODE_PRIVATE);
        device=prefs.getString("id",null);if(device==null){device=UUID.randomUUID().toString();prefs.edit().putString("id",device).commit();}
        try{pairing=new GazPairing(store,device);}catch(Exception e){new AlertDialog.Builder(this).setTitle("تعذر التحقق من هوية الجهاز").setMessage("بياناتك محفوظة. لا تحذف التطبيق.").setPositiveButton("إغلاق",(d,w)->finish()).setCancelable(false).show();return;}
        if(saved!=null){scanMode=saved.getString("scanMode");scanAdmin=saved.getString("scanAdmin");}
        cleanCache();
        if(saved!=null){restoreAdmin=saved.getString("restoreAdmin");String path=saved.getString("pending");if(path!=null){File f=new File(path);if(f.isFile()&&f.getParentFile().equals(outDir()))pending=f;}}
        web=new WebView(this);
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setAllowFileAccessFromFileURLs(false);s.setAllowUniversalAccessFromFileURLs(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setJavaScriptCanOpenWindowsAutomatically(false);s.setSupportMultipleWindows(false);
        WebViewAssetLoader loader=new WebViewAssetLoader.Builder().addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                Uri u=r.getUrl();if("https".equals(u.getScheme())&&"appassets.androidplatform.net".equals(u.getHost())&&u.getPath()!=null&&u.getPath().startsWith("/assets/")){
                    WebResourceResponse res=loader.shouldInterceptRequest(u);if(res!=null)return res;
                }return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !HOME.equals(r.getUrl().toString());}
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(),"GazNative");setContentView(web);web.loadUrl(HOME);
    }
    private final class Bridge {
        @JavascriptInterface public String readState(){try{if(!permitValid())return "";return isDistributor()?store.distributorWebState():store.readState();}catch(Exception e){return "";}}
        @JavascriptInterface public boolean writeState(String json){try{if(!permitValid())return false;if(distributorSetupOnly()){JSONObject n=new JSONObject(json);for(String k:new String[]{"families","cycles","deliveries"})if(n.getJSONArray(k).length()!=0)return false;}store.writeLocalState(json);return true;}catch(Exception e){return false;}}
        @JavascriptInterface public String licensedRole(){return permitValid()?activePermit.role:"";}
        @JavascriptInterface public void licenseInfo(){runOnUiThread(()->showPermitInfo());}
        @JavascriptInterface public String deviceId(){return device;}
        @JavascriptInterface public boolean biometricEnabled(){return permitValid()&&biometric!=null&&biometric.enabled();}
        @JavascriptInterface public void biometricLogin(){runOnUiThread(()->loginWithBiometric());}
        @JavascriptInterface public void biometricSettings(String id){runOnUiThread(()->MainActivity.this.biometricSettings(id));}
        @JavascriptInterface public void excelTemplate(String admin){runOnUiThread(()->exportExcelTemplate(admin));}
        @JavascriptInterface public void importExcel(String admin){runOnUiThread(()->chooseExcel(admin));}
        @JavascriptInterface public void openContact(String type){runOnUiThread(()->contact(type));}
        @JavascriptInterface public String deviceRole(){try{return store.role();}catch(Exception e){return "locked";}}
        @JavascriptInterface public void manageDevices(String admin){runOnUiThread(()->manageDevicesScreen(admin));}
        @JavascriptInterface public String allocatedRows(){try{if(!permitValid())return "[]";return isDistributor()?"[]":pairing.allocations().toString();}catch(Exception e){return "";} }
        @JavascriptInterface public void allocatedDistribution(String admin){runOnUiThread(()->distributorScreen(admin));}
        @JavascriptInterface public synchronized String distributorLogin(String username,String password){
            try{if(!permitValid()||!isDistributor())return "";long now=android.os.SystemClock.elapsedRealtime();if(now-lastLoginAttempt<700&&lastLoginAttempt!=0)return "";lastLoginAttempt=now;
                JSONObject u=DistributorUsers.login(store.distributorUsers(),username,password);if(u==null)return "";distributorSessionId=u.getString("id");return DistributorUsers.publicUser(u).toString();
            }catch(Exception e){return "";}
        }
        @JavascriptInterface public String distributorSession(){try{JSONObject u=sessionUser();return u==null?"":DistributorUsers.publicUser(u).toString();}catch(Exception e){return "";}}
        @JavascriptInterface public void distributorLogout(){if(isDistributor())distributorSessionId=null;}
        @JavascriptInterface public void distributorAction(String action){runOnUiThread(()->openDistributorAction(action));}
        @JavascriptInterface public String distributorSearchRows(){
            try{JSONObject user=sessionUser();if(!DistributorUsers.allowed(user,"view")&&!DistributorUsers.allowed(user,"deliver"))return "";JSONArray all=pairing.allocations(),rows=new JSONArray();
                for(int i=0;i<all.length();i++){JSONObject row=all.getJSONObject(i),safe=new JSONObject();for(String k:new String[]{"key","name","no","house","square","cycleTitle","done","synced","locked","corrected"})if(row.has(k))safe.put(k,row.get(k));rows.put(safe);}return rows.toString();
            }catch(Exception e){return "";}
        }
        @JavascriptInterface public void openDistributorSearchResult(String key){runOnUiThread(()->showDistributorSearchResult(key));}
        @JavascriptInterface public void motherDelivery(String admin,String cycle,String family,String action){runOnUiThread(()->motherDeliveryDialog(admin,cycle,family,action));}
        @JavascriptInterface public void deliveryAudit(String admin){runOnUiThread(()->showDeliveryAudit(admin));}
        @JavascriptInterface public void motherDeliveryBatch(String admin,String cycle,String action,String json){runOnUiThread(()->motherDeliveryBatchDialog(admin,cycle,action,json));}
        @JavascriptInterface public void centralAccounts(String admin){runOnUiThread(()->centralDevices(admin));}
        @JavascriptInterface public void chooseLoginRole(String admin,String selected){runOnUiThread(()->setupLoginRole(admin,selected));}
        @JavascriptInterface public void refreshAccountPolicy(){runOnUiThread(()->{if(isDistributor())scanQr("policy","device-policy");});}
        @JavascriptInterface public void backup(String admin){runOnUiThread(()->{if(backupAllowed(admin))askPass(true,null,false,admin);});}
        @JavascriptInterface public String backupEmail(String admin){try{return !isDistributor()&&store.admin(admin)?savedBackupEmail():"";}catch(Exception e){return "";}}
        @JavascriptInterface public boolean saveBackupEmail(String admin,String email){try{if(isDistributor()||!store.admin(admin))return false;String value=email==null?"":email.trim();if(!value.isEmpty()&&(value.length()>254||!android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches()))return false;return getSharedPreferences("gaz_backup_destination",MODE_PRIVATE).edit().putString("email",value).commit();}catch(Exception e){return false;}}
        @JavascriptInterface public void cloudBackup(String admin){runOnUiThread(()->chooseCloudBackup(admin));}
        @JavascriptInterface public void restore(String admin){runOnUiThread(()->{if(!backupAllowed(admin))return;closeLan();restoreAdmin=admin;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(i,RESTORE);}catch(Exception e){restoreAdmin=null;msg("تعذر فتح الملفات");}});}
        @JavascriptInterface public void shareText(String text){if(!licensedRole("mother")||isDistributor())return;runOnUiThread(()->{Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,text);startActivity(Intent.createChooser(i,"مشاركة الكشف"));});}
        @JavascriptInterface public void exportDocument(String name,String mime,String b64){if(!licensedRole("mother")||isDistributor())return;document(name,mime,b64,0);}
        @JavascriptInterface public void shareDocument(String name,String mime,String b64){if(!licensedRole("mother")||isDistributor())return;document(name,mime,b64,1);}
        @JavascriptInterface public void printDocument(String name,String b64){if(!licensedRole("mother")||isDistributor())return;document(name,"application/pdf",b64,2);}
    }
    private boolean isDistributor(){try{return "distributor".equals(store.role());}catch(Exception e){return false;}}
    private JSONObject sessionUser()throws Exception{String id=distributorSessionId;return id==null||!permitValid()||!isDistributor()?null:DistributorUsers.find(store.distributorUsers(),id);}
    private boolean distributorAllowed(String permission){
        if(!permitValid()||!"distributor".equals(activePermit.role))return false;
        try{if(DistributorUsers.allowed(sessionUser(),permission))return true;}catch(Exception ignored){}
        msg("لا توجد صلاحية لتنفيذ هذا الإجراء");return false;
    }
    private boolean syncAllowed(String id){return isDistributor()?distributorAllowed("sync"):adminAllowed(id);}
    private boolean adminAllowed(String admin){
        if(!permitValid())return false;
        if(isDistributor())return distributorAllowed("admin");
        try{if(admin!=null&&store.admin(admin))return true;}catch(Exception ignored){}
        msg("يلزم حساب مدير الجهاز");return false;
    }
    private void notifyPairChange(){if(web!=null)web.evaluateJavascript("window.location.reload()",null);}
    private LinearLayout pairLayout(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);int d=(int)(14*getResources().getDisplayMetrics().density);v.setPadding(d,d,d,d);return v;}
    private void pairText(LinearLayout p,String text){TextView t=new TextView(this);t.setText(text);t.setTextSize(14);float d=getResources().getDisplayMetrics().density;t.setPadding(0,(int)(4*d),0,(int)(8*d));p.addView(t);}
    private void pairButton(LinearLayout p,String text,Runnable action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(14);b.setMinHeight((int)(44*getResources().getDisplayMetrics().density));p.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->action.run());}
    private void manageDevicesScreen(String admin){
        if(!adminAllowed(admin))return;
        try{
            JSONObject s=pairing.status();String role=s.optString("role","independent");
            LinearLayout p=pairLayout();pairText(p,"معرّف بصمة الجهاز: "+pairing.fingerprint());
            pairText(p,"المزامنة المحلية عبر Wi-Fi");
            ScrollView scroll=new ScrollView(this);scroll.addView(p);
            AlertDialog d=new AlertDialog.Builder(this).setTitle("إدارة الجوالات").setView(scroll).setPositiveButton("إغلاق",null).create();
            if(role.equals("independent")){
                pairText(p,"هذا الجهاز مستقل الآن.");
                if(licensedRole("mother"))pairButton(p,"تعيين هذا الجهاز جوالًا أم",()->new AlertDialog.Builder(this).setTitle("تعيين الجوال الأم").setMessage("سيصبح هذا الهاتف الجهاز الأم لإدارة الكيان أو المرفق وربط الموزعين. نقل صلاحية الجهاز الأم إلى جهاز آخر غير متاح حاليًا؛ احفظ نسخة احتياطية قبل الربط.").setNegativeButton("إلغاء",null).setPositiveButton("تأكيد",(x,y)->{try{if(!adminAllowed(admin)||!licensedRole("mother"))return;pairing.becomeMother();d.dismiss();notifyPairChange();manageDevicesScreen(admin);}catch(Exception e){msg("تعذر التعيين؛ تحقق من وجود حساب مدير وحالة الربط");}}).show());
                if(licensedRole("distributor"))pairButton(p,"ربط هذا الجهاز كموزع",()->{EditText name=new EditText(this);name.setSingleLine(true);name.setHint("اسم الموزع أو الجهاز");new AlertDialog.Builder(this).setTitle("طلب الاقتران").setMessage("يجب أن تكون سجلات الأسر والدورات والتسليم فارغة في هذا الجهاز.").setView(name).setNegativeButton("إلغاء",null).setPositiveButton("إظهار QR",(x,y)->{try{if(!adminAllowed(admin)||!licensedRole("distributor"))return;String packet=pairing.request(name.getText().toString());d.dismiss();notifyPairChange();showQr("طلب ربط الموزع",packet,pairing.fingerprint(), "امسح هذا الرمز بالجوال الأم وقارن البصمة.");}catch(Exception e){msg("تعذر إنشاء الطلب؛ اختر اسمًا وتأكد أن الجهاز فارغ");}}).show();});
            }else if(role.equals("pending")){
                pairText(p,"بانتظار موافقة الجوال الأم.");
                pairButton(p,"عرض طلب الربط QR",()->{try{showQr("طلب ربط الموزع",s.getString("request"),pairing.fingerprint(),"امسح الطلب بالجوال الأم وقارن البصمة.");}catch(Exception e){msg("تعذر عرض الرمز");}});
                pairButton(p,"مسح رمز موافقة الجوال الأم",()->{d.dismiss();scanQr("approval",admin);});
                pairButton(p,"إلغاء الطلب المعلق",()->new AlertDialog.Builder(this).setTitle("إلغاء الطلب؟").setMessage("إذا سبق للجوال الأم اعتماد الطلب فسيظل تسجيله هناك. لا توجد تخصيصات في هذا الإصدار.").setNegativeButton("رجوع",null).setPositiveButton("إلغاء الطلب",(x,y)->{try{if(!adminAllowed(admin))return;pairing.cancelPending();d.dismiss();notifyPairChange();}catch(Exception e){msg("تعذر الإلغاء");}}).show());
            }else if(role.equals("mother")){
                pairText(p,"الجهاز الأم");if(!"غير محدد".equals(s.optString("area")))pairText(p,"نطاق الربط: "+s.optString("area"));
                pairButton(p,"مسح طلب ربط موزع جديد",()->{d.dismiss();scanQr("request",admin);});
                JSONObject devices=s.getJSONObject("devices");
                pairText(p,"الأجهزة المسجلة: "+devices.length());
                pairButton(p,"الإدارة المركزية لمستخدمي الموزعين",()->{d.dismiss();centralDevices(admin);});
                pairButton(p,"تخصيص أسر لدورة توزيع",()->{d.dismiss();chooseAllocation(admin);});
                pairButton(p,"عرض التخصيصات وحالات التسليم",()->{d.dismiss();motherAllocations(admin);});
                pairButton(p,"فتح المزامنة على الشبكة المحلية",()->{d.dismiss();chooseLan(admin);});
                for(java.util.Iterator<String> it=devices.keys();it.hasNext();){
                    JSONObject child=devices.getJSONObject(it.next());
                    pairText(p,child.getString("name")+" | "+PairPackets.shortFingerprint(child.getString("pub")));
                    String approval=child.getString("approval");pairButton(p,"عرض رمز الموافقة مجددًا",()->{try{showQr("موافقة الجوال الأم",approval,pairing.fingerprint(),"امسح الرمز من جوال الموزع وقارن بصمة الأم.");}catch(Exception e){msg("تعذر عرض الرمز");}});
                }
            }else if(role.equals("distributor")){
                pairText(p,"موزع مرتبط بالجهاز الأم — نطاق الربط: "+s.getString("area"));
                pairText(p,"بصمة الجوال الأم: "+PairPackets.shortFingerprint(s.getString("motherPub")));
                pairText(p,s.has("lastSync")?"آخر مزامنة: "+new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.ROOT).format(new java.util.Date(s.getLong("lastSync"))):"لم تتم المزامنة بعد.");
                pairButton(p,"مزامنة بمسح رمز QR للجوال الأم",()->{d.dismiss();scanQr("sync",admin);});
                pairButton(p,"إدخال العنوان يدويًا (احتياطي)",()->{d.dismiss();connectLan(admin);});
                pairButton(p,"تسجيل توزيع الأسر المخصصة",()->{d.dismiss();distributorScreen(admin);});
            }
            d.show();
        }catch(Exception e){msg("تعذر فتح إدارة الجوالات؛ لم يتم تغيير بياناتك");}
    }
    private void setupLoginRole(String admin,String selected){
        if(!adminAllowed(admin))return;
        try{if(!"independent".equals(store.role()))return;
            if(!licensedRole(selected)){msg("نوع الجهاز لا يطابق التصريح الصادر");return;}if("distributor".equals(selected)){manageDevicesScreen(admin);return;}
            if(!"mother".equals(selected))return;
            new AlertDialog.Builder(this).setTitle("تعيين الجهاز الأم").setMessage("هل تريد تعيين هذا الجهاز جهازًا أم لإدارة الكيان أو المرفق؟ لا يُشترط إدخال اسم الكيان. لا يمكن تغيير الدور من شاشة الدخول بعد الاقتران.").setNegativeButton("لاحقًا",null).setPositiveButton("تعيين الأم",(v,w)->{try{if(!adminAllowed(admin)||!licensedRole("mother"))return;pairing.becomeMother();notifyPairChange();}catch(Exception e){msg("تعذر تعيين الجهاز الأم؛ تحقق من حالة الربط وصلاحية المدير");}}).show();
        }catch(Exception e){msg("تعذر التحقق من نوع الجهاز");}
    }
    private void centralDevices(String admin){
        if(!adminAllowed(admin))return;
        try{JSONObject s=pairing.status();PairPackets.require("mother".equals(s.getString("role")),"Mother required");JSONObject devices=s.getJSONObject("devices");
            LinearLayout p=pairLayout();pairText(p,"اختر الموزع. تُطبق التغييرات عند مزامنته؛ يتأكد وصولها عند المزامنة التالية.");
            ScrollView sc=new ScrollView(this);sc.addView(p);AlertDialog d=new AlertDialog.Builder(this).setTitle("الإدارة المركزية للموزعين").setView(sc).setPositiveButton("إغلاق",null).create();
            if(devices.length()==0)pairText(p,"لا توجد جوالات مرتبطة.");
            for(java.util.Iterator<String> it=devices.keys();it.hasNext();){String id=it.next();JSONObject child=devices.getJSONObject(id),policy=CentralUsers.policy(s,id),status=CentralUsers.status(s,id);
                long rev=CentralUsers.rev(policy),applied=status==null?0:status.optLong("applied");String detail=rev==0?(status==null?"حدّث الموزع وزامن أولًا":"جاهز لتفعيل الإدارة"):(applied==rev?"مؤكدة — "+rev:"بانتظار تأكيد وصول الإصدار "+rev);
                pairButton(p,child.getString("name")+" — "+detail,()->{d.dismiss();centralDevice(admin,id);});}
            d.show();
        }catch(Exception e){msg("تعذر عرض الأجهزة المرتبطة");}
    }
    private void centralDevice(String admin,String child){
        if(!adminAllowed(admin))return;
        try{JSONObject s=pairing.status(),device=SyncRules.child(s,child),policy=CentralUsers.policy(s,child),seen=CentralUsers.status(s,child);
            LinearLayout p=pairLayout();ScrollView sc=new ScrollView(this);sc.addView(p);
            AlertDialog d=new AlertDialog.Builder(this).setTitle("مستخدمو "+device.getString("name")).setView(sc).setPositiveButton("إغلاق",null).create();
            if(policy==null){
                if(seen==null||!seen.has("roster"))pairText(p,"حدّث هذا الجوال وزامن مرة واحدة لاستيراد أسماء حساباته.");
                else{JSONArray roster=seen.getJSONArray("roster");for(int i=0;i<roster.length();i++)pairText(p,roster.getJSONObject(i).getString("username"));
                    pairButton(p,"تفعيل الإدارة المركزية",()->new AlertDialog.Builder(this).setTitle("تفعيل الإدارة المركزية؟").setMessage("تنتقل إدارة الصلاحيات إلى الأم بعد المزامنة. تبقى كلمات المرور القديمة على الموزع؛ وتبقى الحركات السابقة محفوظة.").setNegativeButton("إلغاء",null).setPositiveButton("تفعيل",(v,w)->{try{if(!adminAllowed(admin))return;pairing.activateCentral(child);d.dismiss();centralDevice(admin,child);}catch(Exception e){msg("تعذر التفعيل");}}).show());}
            }else{
                long rev=CentralUsers.rev(policy),applied=seen==null?0:seen.optLong("applied");pairText(p,applied==rev?"الصلاحيات مؤكدة":"بانتظار تأكيد وصول التعديلات (الإصدار "+rev+")");
                pairButton(p,"إضافة مستخدم",()->{d.dismiss();centralEdit(admin,child,null);});
                JSONArray a=policy.getJSONArray("users");for(int i=0;i<a.length();i++){JSONObject u=a.getJSONObject(i);StringBuilder labels=new StringBuilder();
                    for(int j=0;j<CentralUsers.PERMS.length;j++)if(u.getJSONObject("permissions").optBoolean(CentralUsers.PERMS[j])){if(labels.length()>0)labels.append("، ");labels.append(CentralUsers.LABELS[j]);}
                    pairText(p,u.getString("username")+" — "+(u.getBoolean("enabled")?"نشط":"موقوف")+"\n"+labels);
                    pairButton(p,"تعديل الصلاحيات / الإيقاف",()->{d.dismiss();centralEdit(admin,child,u);});}
            }d.show();
        }catch(Exception e){msg("تعذر عرض حسابات الموزع");}
    }
    private void centralEdit(String admin,String child,JSONObject old){
        if(!adminAllowed(admin))return;
        LinearLayout p=pairLayout();EditText name=new EditText(this),password=new EditText(this);
        name.setSingleLine(true);name.setHint("اسم المستخدم");password.setHint(old==null?"كلمة المرور (6 أحرف فأكثر)":"كلمة مرور جديدة (اختياري)");password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if(old!=null)name.setText(old.optString("username"));p.addView(name);p.addView(password);
        CheckBox eye=new CheckBox(this);eye.setText("إظهار كلمة المرور");eye.setOnCheckedChangeListener((v,on)->{password.setTransformationMethod(on?null:android.text.method.PasswordTransformationMethod.getInstance());password.setSelection(password.length());});p.addView(eye);
        CheckBox active=new CheckBox(this);active.setText("الحساب نشط");active.setChecked(old==null||old.optBoolean("enabled",true));p.addView(active);
        CheckBox[] checks=new CheckBox[CentralUsers.PERMS.length];for(int i=0;i<checks.length;i++){checks[i]=new CheckBox(this);checks[i].setText(CentralUsers.LABELS[i]);checks[i].setChecked(old==null?"view".equals(CentralUsers.PERMS[i]):old.optJSONObject("permissions").optBoolean(CentralUsers.PERMS[i]));p.addView(checks[i]);}
        ScrollView sc=new ScrollView(this);sc.addView(p);AlertDialog d=new AlertDialog.Builder(this).setTitle(old==null?"إضافة مستخدم موزع":"تعديل مستخدم موزع").setView(sc).setNegativeButton("إلغاء",null).setPositiveButton("حفظ",null).create();
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{try{if(!adminAllowed(admin))return;JSONObject perms=new JSONObject();for(int i=0;i<checks.length;i++)perms.put(CentralUsers.PERMS[i],checks[i].isChecked());pairing.editCentral(child,old==null?null:old.getString("id"),name.getText().toString(),password.getText().toString(),perms,active.isChecked());d.dismiss();centralDevice(admin,child);msg("تم الحفظ؛ بانتظار المزامنة");}catch(Exception e){msg("تعذر الحفظ؛ راجع الاسم وكلمة المرور");}}));d.show();
    }
    private void chooseAllocation(String admin){
        if(!adminAllowed(admin))return;
        try{
            JSONObject s=pairing.status(),data=new JSONObject(store.readState()),devices=s.getJSONObject("devices");
            java.util.ArrayList<String> ids=new java.util.ArrayList<>(),names=new java.util.ArrayList<>(),cycles=new java.util.ArrayList<>(),titles=new java.util.ArrayList<>();
            for(java.util.Iterator<String> it=devices.keys();it.hasNext();){String k=it.next();ids.add(k);names.add(devices.getJSONObject(k).getString("name"));}
            JSONArray list=data.getJSONArray("cycles");for(int i=0;i<list.length();i++){JSONObject c=list.getJSONObject(i);if(!c.optBoolean("closed")){cycles.add(c.getString("id"));titles.add(c.getString("title")+" · "+c.getString("date"));}}
            if(ids.isEmpty()||cycles.isEmpty()){msg("يلزم موزع مرتبط ودورة توزيع مفتوحة");return;}
            new AlertDialog.Builder(this).setTitle("اختر دورة التوزيع").setItems(titles.toArray(new String[0]),(d,i)->new AlertDialog.Builder(this).setTitle("اختر الموزع").setItems(names.toArray(new String[0]),(x,j)->chooseFamilies(admin,ids.get(j),cycles.get(i))).setNegativeButton("إلغاء",null).show()).setNegativeButton("إلغاء",null).show();
        }catch(Exception e){msg("تعذر قراءة التخصيصات");}
    }
    private void chooseFamilies(String admin,String child,String cycle){
        if(!adminAllowed(admin))return;
        try{
            JSONObject state=pairing.status(),data=new JSONObject(store.readState()),c=SyncRules.find(data.getJSONArray("cycles"),cycle);
            AllocationSelection selection=new AllocationSelection(data,SyncRules.map(state,"grants"),cycle);
            if(selection.total()==0){msg("لا توجد أسر متاحة للتخصيص في هذه الدورة");return;}
            String childName=SyncRules.child(state,child).getString("name");
            LinearLayout p=pairLayout();pairText(p,"الموزع: "+childName+" | "+c.getString("title")+"\nالاستحقاق لكل أسرة: "+"تحسب الكمية بحسب عدد أفراد الأسرة");
            EditText search=new EditText(this);search.setSingleLine(true);search.setHint("بحث بالاسم أو الرقم أو رابط الأسرة أو المربع");p.addView(search);
            java.util.ArrayList<String> squareKeys=new java.util.ArrayList<>(),squareNames=new java.util.ArrayList<>();
            squareKeys.add(null);squareNames.add("جميع المستفيدين المتاحين ("+selection.total()+")");
            for(java.util.Map.Entry<String,String> e:selection.squares().entrySet()){squareKeys.add(e.getKey());squareNames.add((e.getKey().isEmpty()?e.getValue():"مربع: "+e.getValue())+" ("+selection.visible("",e.getKey()).size()+")");}
            Spinner squares=new Spinner(this);ArrayAdapter<String> squareAdapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,squareNames);squareAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);squares.setAdapter(squareAdapter);p.addView(squares);
            TextView summary=new TextView(this);summary.setPadding(0,8,0,8);p.addView(summary);
            LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);p.addView(actions,new LinearLayout.LayoutParams(-1,-2));
            Button select=new Button(this),unselect=new Button(this);select.setAllCaps(false);unselect.setAllCaps(false);actions.addView(select,new LinearLayout.LayoutParams(0,-2,1));actions.addView(unselect,new LinearLayout.LayoutParams(0,-2,1));unselect.setText("إلغاء تحديد الظاهر");
            Button clear=new Button(this);clear.setAllCaps(false);clear.setText("إلغاء جميع التحديدات");p.addView(clear);
            ListView list=new ListView(this);list.setDividerHeight(1);list.setChoiceMode(ListView.CHOICE_MODE_NONE);p.addView(list,new LinearLayout.LayoutParams(-1,(int)(Math.min(300,getResources().getDisplayMetrics().heightPixels/getResources().getDisplayMetrics().density*0.32f)*getResources().getDisplayMetrics().density)));
            java.util.ArrayList<JSONObject> visible=new java.util.ArrayList<>();
            BaseAdapter adapter=new BaseAdapter(){
                public int getCount(){return visible.size();}
                public Object getItem(int i){return visible.get(i);}
                public long getItemId(int i){return i;}
                public android.view.View getView(int pos,android.view.View reused,android.view.ViewGroup parent){
                    CheckedTextView v=reused instanceof CheckedTextView?(CheckedTextView)reused:(CheckedTextView)getLayoutInflater().inflate(android.R.layout.simple_list_item_multiple_choice,parent,false);
                    JSONObject f=visible.get(pos);v.setText(AllocationSelection.label(f)+" | الاستحقاق: "+AidQuota.forFamily(c,f));v.setChecked(selection.checked(f.optString("id")));v.setTextSize(15);v.setSingleLine(false);v.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);return v;
                }
            };
            list.setAdapter(adapter);
            Runnable update=()->{int checked=selection.countVisible(visible);summary.setText("المحدد: "+selection.count()+" من "+selection.total()+" | الظاهر: "+visible.size()+" (محدد "+checked+")");select.setEnabled(checked<visible.size());unselect.setEnabled(checked>0);clear.setEnabled(selection.count()>0);};
            Runnable render=()->{int idx=squares.getSelectedItemPosition();String square=idx<0?null:squareKeys.get(idx);visible.clear();visible.addAll(selection.visible(search.getText().toString(),square));select.setText(AllocationSelection.norm(search.getText().toString()).isEmpty()?(square==null?"تحديد الجميع":"تحديد المربع بالكامل"):"تحديد نتائج البحث");adapter.notifyDataSetChanged();update.run();};
            select.setOnClickListener(v->{selection.setAll(visible,true);adapter.notifyDataSetChanged();update.run();});
            unselect.setOnClickListener(v->{selection.setAll(visible,false);adapter.notifyDataSetChanged();update.run();});
            clear.setOnClickListener(v->{selection.clear();adapter.notifyDataSetChanged();update.run();});
            list.setOnItemClickListener((parent,v,pos,id)->{String f=visible.get(pos).optString("id");selection.set(f,!selection.checked(f));adapter.notifyDataSetChanged();update.run();});
            squares.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> parent,android.view.View view,int position,long id){render.run();}public void onNothingSelected(AdapterView<?> parent){}});
            search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){render.run();}public void afterTextChanged(android.text.Editable e){}});
            render.run();
            AlertDialog dlg=new AlertDialog.Builder(this).setTitle("تخصيص الأسر جماعيًا").setView(p).setNegativeButton("إلغاء",null).setPositiveButton("اعتماد التخصيص",null).create();
            dlg.setOnShowListener(v->dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
                if(selection.count()==0){msg("حدد أسرة واحدة على الأقل");return;}
                final java.util.List<String> ids=selection.ids();
                new AlertDialog.Builder(this).setTitle("تأكيد تخصيص "+ids.size()+" أسرة؟").setMessage("الموزع: "+childName+"\nالدورة: "+c.optString("title")+"\nسيتم حجز الأسر المحددة حصريًا لهذا الموزع. تشمل العملية أي أسر حددتها في مربعات أو نتائج بحث سابقة.").setNegativeButton("رجوع",null).setPositiveButton("تأكيد",(x,y)->{
                    if(!adminAllowed(admin))return;
                    ProgressDialog busy=ProgressDialog.show(this,"التخصيص","جارٍ حفظ التخصيصات…",true,false);
                    workers.execute(()->{try{synchronized(store){if(!store.admin(admin))throw new SecurityException("Admin required");pairing.allocate(child,cycle,ids);}runOnUiThread(()->{busy.dismiss();dlg.dismiss();notifyPairChange();msg("تم تخصيص "+ids.size()+" أسرة؛ بانتظار المزامنة");});}catch(Exception e){runOnUiThread(()->{busy.dismiss();msg("تعذر التخصيص؛ قد تكون إحدى الأسر حُجزت أو استلمت بالفعل. لم تُعتمد دفعة جزئية.");});}});
                }).show();
            }));dlg.show();dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }catch(Exception e){msg("تعذر فتح قائمة الأسر");}
    }
    
    private void motherDeliveryDialog(String admin,String cycle,String family,String action){
        if(!adminAllowed(admin))return;
        try{String key=SyncRules.key(cycle,family);JSONObject state=pairing.status();if(!"mother".equals(state.getString("role")))return;
            JSONObject local=new JSONObject(store.readState()),grant=SyncWire.peek(SyncRules.map(state,"grants").getString(key)),f=SyncRules.find(local.getJSONArray("families"),family),c=SyncRules.find(local.getJSONArray("cycles"),cycle),d=SyncRules.delivery(local,cycle,family);
            if(c==null||c.optBoolean("closed")){msg("أعد فتح الدورة أولًا");return;}
            boolean resolved=MotherDelivery.resolved(state,key);
            if("recall".equals(action)){if(resolved||MotherDelivery.control(state,key)!=null){msg("تمت التسوية أو طلب الاسترداد بالفعل");return;}}
            else if(!resolved){msg("استرد صلاحية التسليم من الموزع أولًا");return;}
            if(("edit".equals(action)||"delete".equals(action))&&d==null||"add".equals(action)&&d!=null){msg("تغيرت حالة الحركة؛ أعد فتح الكشف");return;}
            if(!java.util.Arrays.asList("recall","add","edit","delete").contains(action))return;
            LinearLayout p=pairLayout();pairText(p,f.getString("name")+" | "+c.getString("title"));
            pairText(p,"recall".equals(action)?"سيُقفل التسليم على الموزع عند المزامنة. يجب تأكيد الإقفال بمزامنة ثانية قبل أن تتسلم الأم الصلاحية.":"سيبقى الإيصال الأصلي محفوظًا رقابيًا. تنتقل النتيجة إلى الموزع عند المزامنة.");
            EditText qty=new EditText(this);qty.setInputType(InputType.TYPE_CLASS_NUMBER);qty.setText(""+(d==null?grant.getInt("maxQty"):d.getInt("q")));
            boolean needsQty="add".equals(action)||"edit".equals(action);if(needsQty){qty.setHint("الكمية");p.addView(qty);}
            EditText reason=new EditText(this);reason.setHint("سبب الإجراء (مطلوب)");reason.setMaxLines(3);reason.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(500)});p.addView(reason);
            String title="recall".equals(action)?"استرداد صلاحية التسليم":"delete".equals(action)?"حذف حركة التسليم":"edit".equals(action)?"تعديل حركة التسليم":"إضافة تسليم من الأم";
            AlertDialog dlg=new AlertDialog.Builder(this).setTitle(title).setView(p).setNegativeButton("إلغاء",null).setPositiveButton("اعتماد",null).create();
            dlg.setOnShowListener(v->dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{try{if(!adminAllowed(admin))return;String why=reason.getText().toString().trim();if(why.isEmpty()){reason.setError("أدخل السبب");return;}int q=needsQty?Integer.parseInt(qty.getText().toString()):0;pairing.motherDelivery(admin,key,action,q,why);dlg.dismiss();notifyPairChange();msg("تم اعتماد الإجراء");}catch(Exception e){msg("تعذر الاعتماد؛ تحقق من حالة الحركة والكمية");}}));dlg.show();
        }catch(Exception e){msg("تعذر فتح حركة التسليم");}
    }
    private void motherDeliveryBatchDialog(String admin,String cycle,String action,String json){
        if(!adminAllowed(admin))return;
        try{JSONObject s=pairing.status(),local=new JSONObject(store.readState()),c=SyncRules.find(local.getJSONArray("cycles"),cycle);if(!"mother".equals(s.getString("role"))||c==null||c.optBoolean("closed"))return;
            boolean deletion="delete".equals(action);if(!deletion&&!"add".equals(action))return;JSONObject requested=new JSONObject(json);int count=requested.length();
            if(deletion){count=0;JSONArray a=local.getJSONArray("deliveries");for(int i=0;i<a.length();i++)if(cycle.equals(a.getJSONObject(i).getString("c")))count++;}
            if(count==0){msg("لا توجد حركات محددة");return;}final int expected=count;
            LinearLayout p=pairLayout();pairText(p,c.getString("title")+" | "+count+" أسرة\n"+(deletion?"ستُحذف جميع حركات الدورة الحالية، مع حفظ سجل التصحيحات والإيصالات الموقعة.":"ستُعتمد الكميات المحددة للأسر المتاحة للأم فقط."));
            EditText reason=new EditText(this);reason.setHint("سبب الإجراء (مطلوب)");reason.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(500)});p.addView(reason);
            AlertDialog d=new AlertDialog.Builder(this).setTitle(deletion?"حذف حركات الكشف":"تسليم جماعي من الأم").setView(p).setNegativeButton("إلغاء",null).setPositiveButton("اعتماد",null).create();
            d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{String why=reason.getText().toString().trim();if(why.isEmpty()){reason.setError("أدخل السبب");return;}if(!adminAllowed(admin))return;
                try{synchronized(store){if(deletion){int current=0;JSONArray a=new JSONObject(store.readState()).getJSONArray("deliveries");for(int i=0;i<a.length();i++)if(cycle.equals(a.getJSONObject(i).getString("c")))current++;if(current!=expected){msg("تغير عدد الحركات؛ أعد فتح الكشف");d.dismiss();return;}}int saved=pairing.motherDeliveryBatch(admin,cycle,action,requested,why);d.dismiss();notifyPairChange();msg("تم اعتماد "+saved+" حركة");}}
                catch(Exception e){msg("تعذر اعتماد الدفعة؛ لم تُحفظ تغييرات جزئية.");}}));d.show();
        }catch(Exception e){msg("تعذر فتح الإجراء الجماعي");}
    }
    private void showDeliveryAudit(String admin){
        if(!adminAllowed(admin))return;
        try{JSONObject s=pairing.status();if(!"mother".equals(s.getString("role")))return;JSONArray log=s.optJSONArray("deliveryAudit");LinearLayout p=pairLayout();ScrollView sc=new ScrollView(this);sc.addView(p);
            if(log==null||log.length()==0)pairText(p,"لا توجد تصحيحات إدارية.");
            else for(int i=log.length()-1;i>=0;i--){JSONObject e=log.getJSONObject(i),g;String k=e.getString("key");JSONObject grants=SyncRules.map(s,"grants");if(grants.has(k))g=SyncWire.peek(grants.getString(k));else{String[] ids=k.split("/");JSONObject local=new JSONObject(store.readState()),f=SyncRules.find(local.getJSONArray("families"),ids[1]),c=SyncRules.find(local.getJSONArray("cycles"),ids[0]);g=new JSONObject().put("name",f==null?ids[1]:f.getString("name")).put("cycleTitle",c==null?ids[0]:c.getString("title"));}JSONObject before=e.optJSONObject("before"),after=e.optJSONObject("after");String a=e.getString("action");String label="edit".equals(a)?"تعديل":"delete".equals(a)?"حذف":"add".equals(a)?"إضافة":"recall".equals(a)?"طلب استرداد":"تأكيد الاسترداد";pairText(p,g.getString("name")+" | "+g.getString("cycleTitle")+"\n"+label+" | "+e.getString("actor")+" | "+e.getString("at")+"\nقبل: "+(before==null?"—":before.optInt("q"))+" | بعد: "+(after==null?"—":after.optInt("q"))+"\nالسبب: "+e.getString("reason"));}
            new AlertDialog.Builder(this).setTitle("سجل تصحيحات التسليم").setView(sc).setPositiveButton("إغلاق",null).show();
        }catch(Exception e){msg("تعذر عرض سجل التصحيحات");}
    }
    private void motherAllocations(String admin){
        if(!adminAllowed(admin))return;
        try{
            JSONArray rows=pairing.allocations();LinearLayout p=pairLayout();pairText(p,"التخصيصات: "+rows.length());
            for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);pairText(p,r.getString("cycleTitle")+" | "+r.getString("name")+" | "+r.getString("distributorName")+" — "+(r.getBoolean("done")?"تم التسليم":r.optBoolean("managed")?"الصلاحية للأم":r.optBoolean("locked")?"بانتظار تأكيد الاسترداد":"بانتظار التسليم"));}
            ScrollView sc=new ScrollView(this);sc.addView(p);new AlertDialog.Builder(this).setTitle("حالة التخصيصات").setView(sc).setPositiveButton("إغلاق",null).show();
        }catch(Exception e){msg("تعذر عرض التخصيصات");}
    }
    private void showDistributorSearchResult(String key){
        try{JSONObject user=sessionUser();boolean deliver=DistributorUsers.allowed(user,"deliver");
            if(!deliver&&!DistributorUsers.allowed(user,"view")){msg("لا توجد صلاحية للبحث");return;}
            JSONArray all=pairing.allocations();JSONObject found=null;for(int i=0;i<all.length();i++){JSONObject r=all.getJSONObject(i);if(r.getString("key").equals(key)){found=r;break;}}
            if(found==null){msg("لم يعد التخصيص متاحًا");return;}final JSONObject row=found;
            LinearLayout p=pairLayout();pairText(p,"رقم المستفيد: "+row.getInt("no"));pairText(p,"العنوان\nرابط الأسرة/رقم المنزل: "+(row.optString("house").isEmpty()?"—":row.optString("house"))+" | مربع: "+row.optString("square","—"));pairText(p,"بيانات التوزيع\nالدورة: "+row.getString("cycleTitle")+"\nالاستحقاق: "+row.getInt("maxQty")+" عطية");pairText(p,"حالة التسليم\n"+deliveryDescription(row));
            AlertDialog d=new AlertDialog.Builder(this).setTitle(row.getString("name")).setView(p).setPositiveButton("إغلاق",null).create();
            if(deliver&&!row.getBoolean("done")&&!row.optBoolean("locked"))pairButton(p,"تسجيل التسليم",()->{d.dismiss();recordDelivery(user.optString("id"),row);});
            d.show();
        }catch(Exception e){msg("تعذر فتح بيانات المستفيد");}
    }
    private void openDistributorAction(String action){
        try{JSONObject user=sessionUser();if(user==null){msg("سجل الدخول أولًا");return;}String id=user.getString("id");
            switch(action){
                case "view":if(distributorAllowed("view"))readDistributorRows(false);break;
                case "deliver":distributorScreen(id);break;
                case "history":if(distributorAllowed("history"))readDistributorRows(true);break;
                case "reports":if(distributorAllowed("reports"))distributorReports();break;
                case "sync":if(distributorAllowed("sync"))scanQr("sync",id);break;
                case "settings":distributorSettings();break;
                default:msg("الإجراء غير متاح");
            }
        }catch(Exception e){msg("تعذر فتح الشاشة");}
    }
    private String deliveryDescription(JSONObject r){
        if(!r.optBoolean("done"))return r.optBoolean("locked")?(r.optBoolean("corrected")?"ألغت الأم حركة التسليم — التسليم مقفل":"استرداد الصلاحية للأم — التسليم مقفل"):"لم يستلم";
        String at=r.optString("at","").replace('T',' ').replace("Z","");
        if(at.length()>16)at=at.substring(0,16);
        String op=r.optString("operator","");
        return "تم التسليم: "+r.optInt("q")+" | "+(r.optBoolean("synced")?"تمت المزامنة":"بانتظار المزامنة")+(at.isEmpty()?"":"\n"+at)+(op.isEmpty()?"":" | "+op);
    }
    private void readDistributorRows(boolean history){pagedDistributorRows(history,false,null);}
    private void pagedDistributorRows(boolean history,boolean deliver,String admin){
        if(!distributorAllowed(deliver?"deliver":history?"history":"view"))return;
        try{JSONArray all=pairing.allocations();LinearLayout p=pairLayout();EditText search=new EditText(this);search.setSingleLine(true);search.setHint("البحث بالاسم أو رابط الأسرة أو المربع أو الدورة");p.addView(search);
            LinearLayout list=pairLayout();ScrollView scroll=new ScrollView(this);scroll.addView(list);int height=(int)(Math.min(340,getResources().getDisplayMetrics().heightPixels/getResources().getDisplayMetrics().density*0.38f)*getResources().getDisplayMetrics().density);p.addView(scroll,new LinearLayout.LayoutParams(-1,height));
            LinearLayout nav=new LinearLayout(this);Button prev=new Button(this),next=new Button(this);TextView info=new TextView(this);prev.setText("السابق");next.setText("التالي");nav.addView(prev,new LinearLayout.LayoutParams(0,-2,1));nav.addView(info,new LinearLayout.LayoutParams(0,-2,2));nav.addView(next,new LinearLayout.LayoutParams(0,-2,1));info.setGravity(android.view.Gravity.CENTER);p.addView(nav);
            AlertDialog dlg=new AlertDialog.Builder(this).setTitle(deliver?"تسجيل التوزيع":history?"سجل التسليم":"الأسر المخصصة").setView(p).setPositiveButton("إغلاق",null).create();int[] page={0};
            Runnable draw=()->{list.removeAllViews();java.util.ArrayList<JSONObject> matches=new java.util.ArrayList<>();String query=AllocationSelection.norm(search.getText().toString());for(int i=0;i<all.length();i++){JSONObject r=all.optJSONObject(i);if(r==null||(history&&!r.optBoolean("done")))continue;String label=r.optInt("no")+" · "+r.optString("name")+" | "+r.optString("house")+" | "+r.optString("square")+" | "+r.optString("cycleTitle");if(AllocationSelection.norm(label).contains(query))matches.add(r);}
                int pages=Math.max(1,(matches.size()+29)/30);page[0]=Math.max(0,Math.min(page[0],pages-1));int startIndex=page[0]*30,endIndex=Math.min(matches.size(),startIndex+30);
                for(int i=startIndex;i<endIndex;i++){JSONObject r=matches.get(i);pairText(list,r.optInt("no")+" · "+r.optString("name")+" | رابط الأسرة: "+r.optString("house")+" | مربع "+r.optString("square")+" | "+r.optString("cycleTitle")+"\n"+deliveryDescription(r));if(deliver&&!r.optBoolean("done")&&!r.optBoolean("locked"))pairButton(list,"تسجيل التسليم",()->{dlg.dismiss();recordDelivery(admin,r);});}
                if(matches.isEmpty())pairText(list,"لا توجد أسر مطابقة");info.setText("صفحة "+(page[0]+1)+" / "+pages+" | "+matches.size());prev.setEnabled(page[0]>0);next.setEnabled(page[0]+1<pages);nav.setVisibility(pages>1?android.view.View.VISIBLE:android.view.View.GONE);scroll.scrollTo(0,0);};
            prev.setOnClickListener(v->{page[0]--;draw.run();});next.setOnClickListener(v->{page[0]++;draw.run();});search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){page[0]=0;draw.run();}public void afterTextChanged(android.text.Editable e){}});draw.run();dlg.show();dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }catch(Exception e){msg("تعذر قراءة السجل");}
    }
    private void distributorReports(){
        try{JSONArray all=pairing.allocations();java.util.LinkedHashMap<String,int[]> sums=new java.util.LinkedHashMap<>();java.util.LinkedHashMap<String,String> labels=new java.util.LinkedHashMap<>();
            for(int i=0;i<all.length();i++){JSONObject r=all.getJSONObject(i);String cycle=r.getString("cycle");labels.put(cycle,r.getString("cycleTitle")+" · "+r.optString("cycleDate"));int[] n=sums.get(cycle);if(n==null){n=new int[5];sums.put(cycle,n);}n[0]++;if(r.optBoolean("done")){n[1]++;n[2]+=r.optInt("q");if(!r.optBoolean("synced"))n[3]++;}else n[4]++;}
            StringBuilder out=new StringBuilder("رفد — كشف توزيع المساعدات — "+pairing.status().getString("area"));
            for(String cycle:sums.keySet()){int[] n=sums.get(cycle);out.append("\n\n").append(labels.get(cycle)).append("\nالأسر المخصصة: ").append(n[0]).append(" | المستلمة: ").append(n[1]).append(" | المتبقية: ").append(n[4]).append("\nالعطايا المسلّمة: ").append(n[2]).append(" | حركات بانتظار المزامنة: ").append(n[3]);}
            if(sums.isEmpty())out.append("\nلا توجد تخصيصات");final String text=out.toString();
            ScrollView sc=new ScrollView(this);LinearLayout p=pairLayout();pairText(p,text);sc.addView(p);
            AlertDialog.Builder d=new AlertDialog.Builder(this).setTitle("الكشوف والتقارير").setView(sc).setPositiveButton("إغلاق",null);
            if(DistributorUsers.allowed(sessionUser(),"share"))d.setNeutralButton("مشاركة الكشف",(v,w)->{if(!distributorAllowed("share")||!distributorAllowed("reports"))return;Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,text);startActivity(Intent.createChooser(i,"مشاركة الكشف"));});d.show();
        }catch(Exception e){msg("تعذر إعداد التقرير");}
    }
    private void contact(String type){
        try{Intent i;switch(type){case "phone":i=new Intent(Intent.ACTION_DIAL,Uri.parse("tel:+967734915588"));break;case "whatsapp":i=new Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/967734915588"));break;case "feedback":i=new Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/967734915588").buildUpon().appendQueryParameter("text","السلام عليكم، لدي مقترح أو ملاحظة بشأن تطبيق رفد:").build());break;case "email":i=new Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:saleh0129@gmail.com"));break;default:return;}startActivity(i);}catch(Exception e){msg("تعذر فتح تطبيق التواصل");}
    }
    private void showContact(){
        LinearLayout p=pairLayout();pairText(p,"رفد – لإدارة المساعدات وتنظيمها\nتطوير: صالح علي أبوبكر الخطيب\nجميع الحقوق محفوظة للمطور.");
        pairButton(p,"اتصال هاتفي: +967 734 915 588",()->contact("phone"));pairButton(p,"واتساب: +967 734 915 588",()->contact("whatsapp"));pairButton(p,"البريد: saleh0129@gmail.com",()->contact("email"));
        new AlertDialog.Builder(this).setTitle("حول التطبيق والدعم").setView(p).setPositiveButton("إغلاق",null).show();
    }
    private void distributorSettings(){
        try{JSONObject user=sessionUser();if(user==null)return;
            JSONObject s=pairing.status();LinearLayout p=pairLayout();pairText(p,"نطاق الربط: "+s.getString("area")+"\nالمستخدم: "+user.getString("username")+"\n"+(s.has("lastSync")?"آخر مزامنة: "+new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.ROOT).format(new java.util.Date(s.getLong("lastSync"))):"لم تتم المزامنة بعد"));
            pairText(p,"بصمة الجوال الأم: "+PairPackets.shortFingerprint(s.getString("motherPub")));
            AlertDialog d=new AlertDialog.Builder(this).setTitle("إعدادات الموزع").setView(p).setPositiveButton("إغلاق",null).create();
            if(pairing.accountRevision()>0)pairText(p,"الحساب والصلاحيات تُدار من الجوال الأم.");else if(DistributorUsers.allowed(user,"admin"))pairButton(p,"المستخدمون والصلاحيات",()->{d.dismiss();distributorUserList();});
            pairButton(p,"تحديث صلاحياتي من الجوال الأم",()->{d.dismiss();scanQr("policy","device-policy");});
            if(DistributorUsers.allowed(user,"sync"))pairButton(p,"المزامنة بعنوان IP (احتياطي)",()->{d.dismiss();connectLan(user.optString("id"));});
            pairButton(p,"الدخول بالبصمة",()->{d.dismiss();biometricSettings(user.optString("id"));});
            pairButton(p,"تصريح التشغيل",()->{d.dismiss();showPermitInfo();});
            pairButton(p,"حول التطبيق والدعم",()->showContact());
            pairText(p,"إدارة التخصيص والاستعادة ونقل السلطة من الجوال الأم غير متاحة للموزع.");
            d.show();
        }catch(Exception e){msg("تعذر فتح الإعدادات");}
    }
    private void distributorUserList(){
        if(!distributorAllowed("admin"))return;
        try{JSONArray list=store.distributorUsers();LinearLayout p=pairLayout();ScrollView sc=new ScrollView(this);sc.addView(p);
            AlertDialog d=new AlertDialog.Builder(this).setTitle("المستخدمون والصلاحيات").setView(sc).setPositiveButton("إغلاق",null).create();
            pairButton(p,"إضافة مستخدم جديد",()->{d.dismiss();editDistributorUser(null);});
            for(int i=0;i<list.length();i++){JSONObject u=list.getJSONObject(i);String id=u.getString("id");StringBuilder perms=new StringBuilder();
                for(int j=0;j<DistributorUsers.PERMS.length;j++){if(DistributorUsers.allowed(u,DistributorUsers.PERMS[j])){if(perms.length()>0)perms.append("، ");perms.append(DistributorUsers.LABELS[j]);}}
                pairText(p,u.getString("username")+"\n"+(perms.length()==0?"بدون صلاحيات":perms.toString()));
                pairButton(p,"تعديل الحساب",()->{d.dismiss();editDistributorUser(u);});
                if(!id.equals(distributorSessionId))pairButton(p,"حذف الحساب",()->new AlertDialog.Builder(this).setTitle("حذف المستخدم؟").setMessage("تبقى حركات التسليم السابقة محفوظة باسم منفذها.").setNegativeButton("إلغاء",null).setPositiveButton("حذف",(v,w)->{try{if(!distributorAllowed("admin"))return;store.removeDistributorUser(distributorSessionId,id);d.dismiss();distributorUserList();}catch(Exception e){msg("تعذر حذف الحساب");}}).show());
            }d.show();
        }catch(Exception e){msg("تعذر عرض المستخدمين");}
    }
    private void editDistributorUser(JSONObject old){
        if(!distributorAllowed("admin"))return;
        LinearLayout p=pairLayout();EditText name=new EditText(this),password=new EditText(this);name.setSingleLine(true);name.setHint("اسم المستخدم");password.setHint(old==null?"رمز المرور (6 أحرف على الأقل)":"رمز مرور جديد (اختياري)");password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if(old!=null)name.setText(old.optString("username"));p.addView(name);p.addView(password);pairText(p,"صلاحيات المستخدم على هذا الجوال فقط");
        CheckBox[] checks=new CheckBox[DistributorUsers.PERMS.length];
        for(int i=0;i<checks.length;i++){checks[i]=new CheckBox(this);checks[i].setText(DistributorUsers.LABELS[i]);checks[i].setChecked(old==null?"view".equals(DistributorUsers.PERMS[i]):old.optJSONObject("permissions").optBoolean(DistributorUsers.PERMS[i]));p.addView(checks[i]);}
        ScrollView sc=new ScrollView(this);sc.addView(p);AlertDialog d=new AlertDialog.Builder(this).setTitle(old==null?"إضافة مستخدم":"تعديل المستخدم").setView(sc).setNegativeButton("إلغاء",null).setPositiveButton("حفظ",null).create();
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{if(!distributorAllowed("admin"))return;try{
            JSONObject perms=new JSONObject();for(int i=0;i<checks.length;i++)perms.put(DistributorUsers.PERMS[i],checks[i].isChecked());store.saveDistributorUser(distributorSessionId,old==null?null:old.getString("id"),name.getText().toString(),password.getText().toString(),perms);d.dismiss();notifyPairChange();distributorUserList();msg("تم حفظ المستخدم وصلاحياته");
            }catch(Exception e){msg("تعذر الحفظ؛ تحقق من الاسم ورمز المرور والصلاحيات");}}));d.show();
    }
    private void distributorScreen(String admin){pagedDistributorRows(false,true,admin);}
    private void recordDelivery(String admin,JSONObject row){
        if(!distributorAllowed("deliver"))return;
        EditText qty=new EditText(this);qty.setInputType(InputType.TYPE_CLASS_NUMBER);qty.setHint("عدد العطايا");qty.setText(""+row.optInt("maxQty"));
        new AlertDialog.Builder(this).setTitle("تسليم "+row.optString("name")).setMessage("الكمية لا تتجاوز "+row.optInt("maxQty")+" عطية. بعد الحفظ لا يمكن تكرار الحركة أو تعديلها في هذه النسخة.").setView(qty).setNegativeButton("إلغاء",null).setPositiveButton("تأكيد التسليم",(d,w)->{try{if(!distributorAllowed("deliver"))return;JSONObject actor=sessionUser();int n=Integer.parseInt(qty.getText().toString());pairing.record(row.getString("key"),n,actor.getString("id"),actor.getString("username"));msg("تم حفظ التسليم محليًا");if(web!=null)web.evaluateJavascript("window.dispatchEvent(new Event('gaz-search-refresh'))",null);distributorScreen(admin);}catch(Exception e){msg("لم يتم الحفظ؛ تحقق من الكمية أو التسليم السابق");distributorScreen(admin);}}).show();
    }
    private void chooseLan(String admin){
        if(!adminAllowed(admin))return;
        try{
            java.util.LinkedHashMap<String,String> addresses=GazNetwork.addresses(this);if(addresses.isEmpty()){msg("اتصل بشبكة Wi-Fi أو شغّل نقطة الاتصال أولًا");return;}
            java.util.ArrayList<String> ips=new java.util.ArrayList<>(addresses.keySet());if(ips.size()==1){startLan(admin,ips.get(0));return;}
            new AlertDialog.Builder(this).setTitle("اختر Wi-Fi أو نقطة الاتصال").setItems(addresses.values().toArray(new String[0]),(d,i)->startLan(admin,ips.get(i))).setNegativeButton("إلغاء",null).show();
        }catch(Exception e){msg("تعذر قراءة عنوان الشبكة");}
    }
    private void startLan(String admin,String ip){
        if(!adminAllowed(admin))return;closeLan();
        LinearLayout p=pairLayout();pairText(p,"امسح رمز المزامنة من جوال الموزع. يجب اتصال الجوالين بالشبكة نفسها.");pairText(p,"العنوان الاحتياطي: "+ip);
        ImageView qr=new ImageView(this);qr.setAdjustViewBounds(true);qr.setVisibility(android.view.View.GONE);p.addView(qr,new LinearLayout.LayoutParams(-1,(int)(250*getResources().getDisplayMetrics().density)));
        TextView status=new TextView(this);status.setText("جارٍ فتح الاتصال…");p.addView(status);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("المزامنة المحلية المشفرة").setView(p).setPositiveButton("إنهاء",null).create();
        d.setOnDismissListener(v->{closeLan();notifyPairChange();});d.show();d.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Socket initialization and I/O are off the UI thread; cancel closes listener.
        network.execute(()->{GazLan server=null;try{
            server=pairing.listen(ip);final GazLan active=server;final Bitmap code=qrBitmap(pairing.syncQr(ip));java.util.concurrent.CountDownLatch visible=new java.util.concurrent.CountDownLatch(1);final boolean[] accept={false};
            runOnUiThread(()->{if(d.isShowing()&&!isFinishing()){lan=active;qr.setImageBitmap(code);qr.setVisibility(android.view.View.VISIBLE);status.setText("بانتظار مسح رمز QR");accept[0]=true;}visible.countDown();});
            if(!visible.await(5,java.util.concurrent.TimeUnit.SECONDS)||!accept[0])return;
            active.serve(pairing::respond,info->runOnUiThread(()->{if(d.isShowing())status.setText(info);}));
        }catch(Exception e){runOnUiThread(()->{if(d.isShowing())status.setText(GazLan.diagnostic(e));});}finally{if(server!=null)server.close();}});
    }
    private void closeLan(){GazLan s=lan;lan=null;if(s!=null)s.close();}
    private void connectLan(String admin){
        if(!syncAllowed(admin))return;
        EditText ip=new EditText(this);ip.setSingleLine(true);ip.setInputType(InputType.TYPE_CLASS_TEXT);ip.setHint("عنوان IP (192.168.x.x)");
        new AlertDialog.Builder(this).setTitle("المزامنة مع الجوال الأم").setMessage("أدخل عنوان Wi-Fi المعروض على شاشة مزامنة الأم.").setView(ip).setNegativeButton("إلغاء",null).setPositiveButton("مزامنة",(d,w)->{
            syncAddress(admin,ip.getText().toString().trim());
        }).show();
    }
    private void syncAddress(String admin,String ip){syncAddress(admin,ip,false);}
    private void syncAddress(String admin,String ip,boolean policyOnly){
        if(policyOnly?!isDistributor():!syncAllowed(admin))return;
        ProgressDialog busy=ProgressDialog.show(this,"المزامنة","جارٍ التحقق وتبادل البيانات…",true,false);
        network.execute(()->{try{long before=pairing.accountRevision();pairing.synchronize(this,ip,policyOnly);boolean changed=pairing.accountRevision()!=before;runOnUiThread(()->{busy.dismiss();if(changed)distributorSessionId=null;notifyPairChange();msg(changed?"تم تحديث الصلاحيات؛ سجل الدخول مجددًا":policyOnly?"تم تحديث الحسابات":"تمت المزامنة بنجاح");});}
            catch(Exception e){runOnUiThread(()->{busy.dismiss();new AlertDialog.Builder(this).setTitle("لم تكتمل المزامنة").setMessage(GazLan.diagnostic(e)+"\nحركاتك محفوظة. أرسل صورة هذه الرسالة عند تكرار المشكلة.").setPositiveButton("إغلاق",null).show();});}});
    }
    @Override protected void onPause(){closeLan();super.onPause();}
    private void scanQr(String mode,String admin){
        if("policy".equals(mode)?!isDistributor():"sync".equals(mode)?!syncAllowed(admin):!adminAllowed(admin))return;
        scanMode=mode;scanAdmin=admin;
        new IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).setCaptureActivity(PortraitCaptureActivity.class).setPrompt("وجّه الكاميرا إلى رمز QR").setBeepEnabled(false).setOrientationLocked(true).initiateScan();
    }
    private void handlePairScan(String mode,String admin,String packet){
        if("policy".equals(mode)?!isDistributor():"sync".equals(mode)?!syncAllowed(admin):!adminAllowed(admin))return;
        try{
            if(mode.equals("sync")||mode.equals("policy")){String ip=pairing.readSyncQr(packet);syncAddress(admin,ip,mode.equals("policy"));}
            else if(mode.equals("request")){
                JSONObject r=PairPackets.unpack(packet,"request");String fp=PairPackets.shortFingerprint(r.getString("pub"));
                new AlertDialog.Builder(this).setTitle("قبول الموزع؟").setMessage("الاسم: "+r.getString("name")+"\nالبصمة: "+fp+"\nقارن البصمة بالتي تظهر على جوال الموزع نفسه. لا توافق إن اختلفت.").setNegativeButton("إلغاء",null).setPositiveButton("البصمة متطابقة",(d,w)->{try{if(!adminAllowed(admin))return;String approval=pairing.approve(packet);notifyPairChange();showQr("موافقة الجوال الأم",approval,pairing.fingerprint(),"امسح هذا الرمز من جهاز الموزع وقارن بصمة الأم.");}catch(Exception e){msg("رفض الطلب؛ تعارض أو جهاز مسجل سابقًا");}}).show();
            }else if(mode.equals("approval")){
                JSONObject r=pairing.inspectApproval(packet);String fp=PairPackets.shortFingerprint(r.getString("pub"));
                new AlertDialog.Builder(this).setTitle("تأكيد الجوال الأم").setMessage("نطاق الربط: "+r.getString("area")+"\nبصمة الأم: "+fp+"\nقارنها بالبصمة المعروضة على الجوال الأم.").setNegativeButton("إلغاء",null).setPositiveButton("البصمة متطابقة",(d,w)->{try{if(!adminAllowed(admin)||!licensedRole("distributor"))return;pairing.accept(packet);notifyPairChange();msg("تم اعتماد هوية الجوال الأم؛ الصرف مقفل حتى التخصيص");}catch(Exception e){msg("تعذر اعتماد الموافقة");}}).show();
            }
        }catch(Exception e){msg("الرمز غير صالح أو لا يخص هذا الجهاز");}
    }
    private Bitmap qrBitmap(String packet)throws Exception{
        BitMatrix bits=new QRCodeWriter().encode(packet,BarcodeFormat.QR_CODE,720,720);int[] pixels=new int[720*720];
        for(int y=0;y<720;y++)for(int x=0;x<720;x++)pixels[y*720+x]=bits.get(x,y)?Color.BLACK:Color.WHITE;
        return Bitmap.createBitmap(pixels,720,720,Bitmap.Config.ARGB_8888);
    }
    private void showQr(String title,String packet,String fp,String desc)throws Exception{
        Bitmap bitmap=qrBitmap(packet);
        LinearLayout p=pairLayout();pairText(p,desc);pairText(p,"البصمة: "+fp);
        ImageView image=new ImageView(this);image.setImageBitmap(bitmap);image.setAdjustViewBounds(true);p.addView(image,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(this);scroll.addView(p);
        new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("إغلاق",null).show();
    }
    private void document(String name,String mime,String encoded,int action){
        workers.execute(()->{try{
            if(encoded.length()>GazStore.MAX*2)throw new IllegalArgumentException();
            if(!"application/pdf".equals(mime)&&!"text/csv".equals(mime))throw new IllegalArgumentException();
            byte[] bytes=Base64.decode(encoded,Base64.DEFAULT);
            if(bytes.length>GazStore.MAX)throw new IllegalArgumentException();
            if("application/pdf".equals(mime)&&(bytes.length<5||bytes[0]!='%'||bytes[1]!='P'||bytes[2]!='D'||bytes[3]!='F'||bytes[4]!='-'))throw new IllegalArgumentException();
            File f=tempFile(name,mime.equals("application/pdf")?".pdf":".csv",bytes);
            runOnUiThread(()->{try{if(action==0)savePicker(f,mime);else if(action==1)share(f,mime);else print(f);}catch(Exception e){msg("تعذر إخراج الملف");}});
        }catch(Exception e){msg("تعذر تجهيز الملف");}});
    }
    private File outDir(){File dir=new File(getCacheDir(),"gaz_exports");dir.mkdirs();return dir;}
    private void cleanCache(){File[] files=outDir().listFiles();if(files!=null)for(File f:files)if(f.lastModified()<System.currentTimeMillis()-86400000L)f.delete();}
    private File tempFile(String requested,String suffix,byte[] bytes) throws Exception {
        String n=requested.replaceAll("[^\\p{L}\\p{N}_.-]","_");
        if(n.endsWith(suffix))n=n.substring(0,n.length()-suffix.length());
        n=n.substring(0,Math.min(55,n.length()));File f=new File(outDir(),n+"_"+UUID.randomUUID().toString().substring(0,8)+suffix);
        try(FileOutputStream out=new FileOutputStream(f)){out.write(bytes);}return f;
    }
    private void share(File f,String mime){Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",f);Intent i=new Intent(Intent.ACTION_SEND);i.setType(mime);i.putExtra(Intent.EXTRA_STREAM,uri);i.setClipData(ClipData.newRawUri("كشف",uri));i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"مشاركة الكشف"));}
    private void savePicker(File f,String mime){if(pending!=null){msg("أكمل عملية الحفظ الحالية أولًا");return;}pending=f;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType(mime);i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_TITLE,f.getName());try{startActivityForResult(i,SAVE);}catch(Exception e){pending=null;msg("تعذر فتح اختيار مكان الحفظ");}}
    private void print(File f) throws Exception {
        final int pages;try(PdfRenderer render=new PdfRenderer(ParcelFileDescriptor.open(f,ParcelFileDescriptor.MODE_READ_ONLY))){pages=render.getPageCount();}
        PrintManager manager=(PrintManager)getSystemService(PRINT_SERVICE);
        manager.print(f.getName(),new PrintDocumentAdapter(){
            @Override public void onLayout(PrintAttributes old,PrintAttributes now,CancellationSignal cancel,LayoutResultCallback cb,Bundle extras){
                if(cancel.isCanceled()){cb.onLayoutCancelled();return;}
                cb.onLayoutFinished(new PrintDocumentInfo.Builder(f.getName()).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(pages).build(),!now.equals(old));
            }
            @Override public void onWrite(PageRange[] selected,ParcelFileDescriptor dst,CancellationSignal cancel,WriteResultCallback cb){
                workers.execute(()->{try(InputStream in=new FileInputStream(f);OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(dst)){
                    byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1){if(cancel.isCanceled()){cb.onWriteCancelled();return;}out.write(buffer,0,n);}out.flush();cb.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                }catch(Exception e){cb.onWriteFailed("تعذر طباعة الكشف");}});
            }
        },new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
    }
    private boolean excelAllowed(String admin){
        try{String r=store.role();if(("mother".equals(r)||"independent".equals(r))&&store.admin(admin))return true;}catch(Exception ignored){}
        msg("هذه الصلاحية لمدير الجوال الأم");return false;
    }
    private void exportExcelTemplate(String admin){if(!excelAllowed(admin))return;workers.execute(()->{try{File f=tempFile("Rafd_Beneficiaries_100",".xlsx",BeneficiaryExcel.template());runOnUiThread(()->{if(excelAllowed(admin))savePicker(f,BeneficiaryExcel.MIME);});}catch(Exception e){msg("تعذر إنشاء نموذج Excel");}});}
    private void chooseExcel(String admin){
        if(!excelAllowed(admin))return;excelAdmin=admin;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);
        try{startActivityForResult(i,IMPORT_EXCEL);}catch(Exception e){excelAdmin=null;msg("تعذر فتح الملفات");}
    }
    private void readExcel(String admin,Uri uri){
        if(!excelAllowed(admin))return;workers.execute(()->{try{
            byte[] raw;try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("لا يمكن قراءة الملف");ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(b.size()+n>BeneficiaryExcel.MAX)throw new IOException("حجم الملف كبير");b.write(buf,0,n);}raw=b.toByteArray();}
            JSONArray input=BeneficiaryExcel.read(raw);JSONObject p=store.importPreview(admin,input);JSONArray valid=p.getJSONArray("valid"),errors=p.getJSONArray("errors");
            runOnUiThread(()->{if(!excelAllowed(admin))return;LinearLayout layout=pairLayout();pairText(layout,"عدد الصفوف: "+input.length()+" | صالحة: "+valid.length()+" | تحتاج تصحيح: "+errors.length());
                for(int j=0;j<errors.length();j++)pairText(layout,errors.optString(j));if(errors.length()==0)for(int j=0;j<valid.length();j++){JSONObject f=valid.optJSONObject(j);pairText(layout,f.optString("name")+" | رابط الأسرة: "+(f.optString("house").isEmpty()?"—":f.optString("house"))+" | مربع "+f.optString("square"));}
                ScrollView sc=new ScrollView(this);sc.addView(layout);AlertDialog.Builder d=new AlertDialog.Builder(this).setTitle(errors.length()>0?"راجع الملف ثم أعد الاستيراد":"معاينة الاستيراد").setView(sc).setNegativeButton("إغلاق",null);
                if(errors.length()==0)d.setPositiveButton("اعتماد "+valid.length()+" مستفيدًا",(v,w)->{if(!excelAllowed(admin))return;workers.execute(()->{try{int count=store.importBeneficiaries(admin,input);runOnUiThread(()->{notifyPairChange();msg("تمت إضافة "+count+" مستفيدًا");});}catch(Exception e){msg("لم يتم الحفظ؛ راجع التكرار أو تغيّر البيانات");}});});d.show();
            });
        }catch(Exception e){String message=e instanceof IOException?e.getMessage():"اختر ملف XLSX مطابقًا للنموذج";runOnUiThread(()->new AlertDialog.Builder(this).setTitle("تعذر الاستيراد").setMessage(message+"\nلم تُضف أي بيانات.").setPositiveButton("إغلاق",null).show());}});
    }
    private String biometricBinding(JSONObject u)throws Exception{
        String raw=store.role()+"|"+device+"|"+u.getString("id")+"|"+u.getString("salt")+"|"+u.getString("hash")+"|"+pairing.accountRevision();
        return PairPackets.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private void biometricSettings(String id){
        try{JSONObject user=store.loginAccount(id);if(user==null||isDistributor()&&!id.equals(distributorSessionId)){msg("سجل الدخول أولًا");return;}
            LinearLayout p=pairLayout();pairText(p,"يُربط الدخول بالبصمة بحساب واحد على هذا الجهاز. يُطلب تأكيد كلمة مرور الحساب عند التفعيل.");
            EditText pw=new EditText(this);pw.setHint("كلمة المرور الحالية");pw.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);p.addView(pw);
            AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle("الدخول بالبصمة").setView(p).setNegativeButton("إلغاء",null);
            if(biometric.enabled())b.setNeutralButton("إلغاء التفعيل",(d,w)->{biometric.disable();msg("تم إلغاء الدخول بالبصمة");});
            AlertDialog d=b.setPositiveButton("تفعيل",null).create();d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{String password=pw.getText().toString();pw.setText("");d.dismiss();workers.execute(()->{try{
                JSONObject current=DistributorUsers.login(store.loginAccounts(),user.getString("username"),password);if(current==null||!id.equals(current.getString("id")))throw new SecurityException();
                JSONObject token=new JSONObject().put("id",id).put("binding",biometricBinding(current));
                runOnUiThread(()->biometric.prompt(token,new LocalBiometric.Result(){public void success(JSONObject p){msg("تم تفعيل الدخول بالبصمة لهذا الحساب");}public void fail(String m){msg(m);}}));
            }catch(Exception e){msg("كلمة المرور غير صحيحة أو تغير الحساب");}});}));d.show();
        }catch(Exception e){msg("تعذر فتح إعدادات البصمة");}
    }
    private void loginWithBiometric(){
        biometric.prompt(null,new LocalBiometric.Result(){
            public void success(JSONObject token){try{JSONObject u=store.loginAccount(token.getString("id"));if(u==null||!biometricBinding(u).equals(token.getString("binding"))){biometric.disable();msg("تغير الحساب أو الصلاحيات؛ سجل بكلمة المرور وأعد التفعيل");return;}
                if(isDistributor())distributorSessionId=u.getString("id");JSONObject safe=isDistributor()?DistributorUsers.publicUser(u):new JSONObject().put("id",u.getString("id")).put("username",u.getString("username")).put("permissions",u.getJSONObject("permissions"));
                if(web!=null)web.evaluateJavascript("window.gazBiometricAuthenticated("+JSONObject.quote(safe.toString())+")",null);
            }catch(Exception e){msg("تعذر دخول الحساب؛ استخدم كلمة المرور");}}
            public void fail(String m){msg(m);}
        });
    }
    private boolean backupAllowed(String admin){
        if(!licensedRole("mother"))return false;
        try{String role=store.role();if(("mother".equals(role)||"independent".equals(role))&&adminAllowed(admin))return true;}catch(Exception ignored){}
        msg("يلزم حساب مدير الجوال الأم أو الجهاز المستقل");return false;
    }
    private String savedBackupEmail(){return getSharedPreferences("gaz_backup_destination",MODE_PRIVATE).getString("email","");}
    private void chooseCloudBackup(String admin){
        if(!backupAllowed(admin))return;String email=savedBackupEmail();
        LinearLayout p=pairLayout();pairText(p,"ستُنشأ نسخة مشفرة بصيغة json.gz. "+(email.isEmpty()?"لم يُحدد بريد بعد.":"البريد المحفوظ: "+email)+"\nاختر وجهة الحفظ؛ الرفع ليس تلقائيًا.");
        AlertDialog d=new AlertDialog.Builder(this).setTitle("نسخ احتياطي سحابي").setView(p).setNegativeButton("إلغاء",null).create();
        pairButton(p,"حفظ في Google Drive",()->{d.dismiss();askPass(true,null,true,admin,"drive",email);});
        pairButton(p,"إرسال إلى البريد الإلكتروني",()->{if(email.isEmpty()){msg("أدخل بريد النسخ السحابي في الإعدادات أولًا");return;}d.dismiss();askPass(true,null,true,admin,"email",email);});
        pairButton(p,"مشاركة مع خدمة أخرى",()->{d.dismiss();askPass(true,null,true,admin,"share","");});d.show();
    }
    private void emailBackup(File f,String email){
        try{Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",f);Intent i=new Intent(Intent.ACTION_SEND);i.setType("application/gzip");i.putExtra(Intent.EXTRA_EMAIL,new String[]{email});i.putExtra(Intent.EXTRA_SUBJECT,"نسخة احتياطية مشفرة – رفد");i.putExtra(Intent.EXTRA_TEXT,"نسخة احتياطية مشفرة من تطبيق رفد. لا ترسل كلمة مرور فك التشفير مع الملف.");i.putExtra(Intent.EXTRA_STREAM,uri);i.setClipData(ClipData.newRawUri("نسخة احتياطية مشفرة",uri));i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i,"اختر Gmail أو تطبيق البريد، ثم تحقق من المستلم وأرسل"));}catch(Exception e){msg("تعذر فتح مشاركة البريد؛ احفظ النسخة محليًا أولًا");}
    }
    private void cloudShare(File f){
        try{Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",f);Intent i=new Intent(Intent.ACTION_SEND);i.setType("application/gzip");i.putExtra(Intent.EXTRA_STREAM,uri);i.setClipData(ClipData.newRawUri("نسخة احتياطية مشفرة",uri));i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"اختر Drive أو خدمة التخزين السحابي"));}catch(Exception e){msg("تعذرت المشاركة؛ استخدم حفظ النسخة محليًا ثم ارفعها إلى السحابة");}
    }
    private void askPass(boolean backup,Uri uri,boolean cloud,String admin){askPass(backup,uri,cloud,admin,"share","");}
    private void askPass(boolean backup,Uri uri,boolean cloud,String admin,String destination,String email){
        if(!backupAllowed(admin))return;
        EditText password=new EditText(this);password.setSingleLine(true);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setHint("كلمة مرور النسخة (8 أحرف فأكثر)");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(backup?"تشفير النسخة الاحتياطية":"فك تشفير النسخة الاحتياطية").setMessage(backup?"احتفظ بكلمة المرور خارج هذا الجوال. لا يمكن استردادها.":"لن تتغير البيانات قبل التحقق والتأكيد.").setView(password).setNegativeButton("إلغاء",null).setPositiveButton("متابعة",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{char[] pass=password.getText().toString().toCharArray();if(pass.length<8){Arrays.fill(pass,'\0');password.setError("8 أحرف على الأقل");return;}password.setText("");dialog.dismiss();
            workers.execute(()->{try{if(backup){byte[] encrypted=store.backup(pass);File f=tempFile("rafd_backup_"+System.currentTimeMillis(),".json.gz",encrypted);runOnUiThread(()->{if(!backupAllowed(admin))return;if(cloud&&"email".equals(destination))emailBackup(f,email);else if(cloud&&"drive".equals(destination)){msg(email.isEmpty()?"اختر Drive في منتقي الملفات":"اختر Drive والحساب "+email+" في منتقي الملفات");savePicker(f,"application/gzip");}else if(cloud)cloudShare(f);else savePicker(f,"application/gzip");});}else{
                byte[] bytes;try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException();bytes=readLimited(in);}
                String json=GazStore.decodeBackup(bytes,pass);JSONObject checked=store.previewRestore(json);boolean mother="mother".equals(store.role());
                String summary="الأسر: "+checked.getJSONArray("families").length()+" | الدورات: "+checked.getJSONArray("cycles").length()+" | حركات التسليم: "+checked.getJSONArray("deliveries").length();
                runOnUiThread(()->new AlertDialog.Builder(this).setTitle("تأكيد استعادة النسخة").setMessage(summary+"\n\n"+(mother?"سيتم استعادة البيانات المتوافقة مع سجل التسليم الحالي، دون تغيير هوية الأم أو التخصيصات أو المستخدمين والصلاحيات.":"ستستبدل البيانات والحسابات المحلية. احفظ نسخة حالية أولًا.")).setNegativeButton("إلغاء",null).setPositiveButton("استعادة",(d,b)->{if(!backupAllowed(admin))return;closeLan();workers.execute(()->{try{synchronized(store){if(!store.admin(admin))throw new SecurityException("Admin required");store.restoreSafe(json);}runOnUiThread(()->{if(web!=null)web.evaluateJavascript("window.dispatchEvent(new Event('gaz-restore-complete'))",null);});msg("تمت الاستعادة بنجاح");}catch(BackupRestore.Refused e){restoreWarning(e.getMessage());}catch(Exception e){restoreWarning("فشلت الاستعادة؛ لم تُعتمد بيانات جديدة");}});}).show());
            }}catch(BackupRestore.Refused e){restoreWarning(e.getMessage());}catch(Exception e){msg(backup?"تعذر إنشاء النسخة الاحتياطية":"تعذر فتح النسخة؛ تحقق من الملف وكلمة المرور");}finally{Arrays.fill(pass,'\0');}});
        }));dialog.show();
    }
    private void restoreWarning(String reason){runOnUiThread(()->new AlertDialog.Builder(this).setTitle("لم تتم الاستعادة").setMessage(reason+"\nلم تُغيّر بيانات الجهاز.").setPositiveButton("إغلاق",null).show());}
    private static byte[] readLimited(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(out.size()+n>GazStore.MAX)throw new IOException("Too large");out.write(b,0,n);}return out.toByteArray();}
    @Override protected void onActivityResult(int req,int result,Intent data){
        IntentResult scan=IntentIntegrator.parseActivityResult(req,result,data);
        if(scan!=null){String mode=scanMode,admin=scanAdmin;scanMode=null;scanAdmin=null;if(scan.getContents()!=null&&mode!=null&&admin!=null)handlePairScan(mode,admin,scan.getContents());return;}
        super.onActivityResult(req,result,data);
        if(req==SAVE){File f=pending;pending=null;if(result==RESULT_OK&&data!=null&&data.getData()!=null&&f!=null){
            Uri uri=data.getData();workers.execute(()->{try(InputStream in=new FileInputStream(f);OutputStream out=getContentResolver().openOutputStream(uri,"w")){if(out==null)throw new IOException();byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.flush();msg("تم حفظ الملف");}catch(Exception e){msg("تعذر حفظ الملف");}});
        }}else if(req==IMPORT_EXCEL){String admin=excelAdmin;excelAdmin=null;if(result==RESULT_OK&&data!=null&&data.getData()!=null&&admin!=null)readExcel(admin,data.getData());}else if(req==RESTORE){String admin=restoreAdmin;restoreAdmin=null;if(result==RESULT_OK&&data!=null&&data.getData()!=null&&admin!=null)askPass(false,data.getData(),false,admin);}
    }
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);if(pending!=null)b.putString("pending",pending.getAbsolutePath());b.putString("scanMode",scanMode);b.putString("scanAdmin",scanAdmin);b.putString("restoreAdmin",restoreAdmin);b.putString("excelAdmin",excelAdmin);}
    private void msg(String s){runOnUiThread(()->Toast.makeText(this,s,Toast.LENGTH_LONG).show());}
    private boolean backCheckPending=false;
    private AlertDialog exitDialog;
    private void confirmExit(){if(isFinishing()||isDestroyed()||(exitDialog!=null&&exitDialog.isShowing()))return;exitDialog=new AlertDialog.Builder(this).setTitle("تأكيد الخروج").setMessage("هل تريد الخروج من تطبيق رفد؟").setNegativeButton("البقاء",null).setPositiveButton("خروج",(dialog,which)->finishAndRemoveTask()).create();exitDialog.show();}
    @Override public void onBackPressed(){if(backCheckPending||(exitDialog!=null&&exitDialog.isShowing()))return;if(web==null){confirmExit();return;}backCheckPending=true;web.evaluateJavascript("(function(){try{return typeof window.rafdHandleBack==='function'&&window.rafdHandleBack();}catch(e){return false;}})()",result->{backCheckPending=false;if(!"true".equals(result))confirmExit();});}
    @Override protected void onResume(){super.onResume();if(web!=null){if(!permitValid()){closeLan();recreate();return;}permitHandler.removeCallbacks(permitTick);permitHandler.postDelayed(permitTick,30000L);}}
    @Override protected void onPause(){permitHandler.removeCallbacks(permitTick);super.onPause();}
    @Override protected void onDestroy(){permitHandler.removeCallbacks(permitTick);if(web!=null){web.removeJavascriptInterface("GazNative");web.destroy();}closeLan();network.shutdownNow();workers.shutdown();super.onDestroy();}
}
