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
    private static final int SAVE=21,RESTORE=22;
    private WebView web;
    private GazStore store;
    private File pending;
    private final ExecutorService workers=Executors.newSingleThreadExecutor();
    private String device;
    private GazPairing pairing;
    private String scanMode, scanAdmin;
    private GazLan lan;
    private volatile String distributorSessionId;
    private long lastLoginAttempt;
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        try{store=new GazStore(this);store.readState();}catch(Exception e){new AlertDialog.Builder(this).setTitle("تعذر فتح البيانات").setMessage("لم تُحذف بياناتك. راجع الدعم الفني.").setPositiveButton("إغلاق",(d,w)->finish()).setCancelable(false).show();return;}
        android.content.SharedPreferences prefs=getSharedPreferences("identity",MODE_PRIVATE);
        device=prefs.getString("id",null);if(device==null){device=UUID.randomUUID().toString();prefs.edit().putString("id",device).commit();}
        try{pairing=new GazPairing(store,device);}catch(Exception e){new AlertDialog.Builder(this).setTitle("تعذر التحقق من هوية الجهاز").setMessage("بياناتك محفوظة. لا تحذف التطبيق.").setPositiveButton("إغلاق",(d,w)->finish()).setCancelable(false).show();return;}
        if(saved!=null){scanMode=saved.getString("scanMode");scanAdmin=saved.getString("scanAdmin");}
        cleanCache();
        if(saved!=null){String path=saved.getString("pending");if(path!=null){File f=new File(path);if(f.isFile()&&f.getParentFile().equals(outDir()))pending=f;}}
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
        @JavascriptInterface public String readState(){try{return isDistributor()?store.distributorWebState():store.readState();}catch(Exception e){return "";}}
        @JavascriptInterface public boolean writeState(String json){try{store.writeLocalState(json);return true;}catch(Exception e){return false;}}
        @JavascriptInterface public String deviceId(){return device;}
        @JavascriptInterface public String deviceRole(){try{return store.role();}catch(Exception e){return "locked";}}
        @JavascriptInterface public void manageDevices(String admin){runOnUiThread(()->manageDevicesScreen(admin));}
        @JavascriptInterface public String allocatedRows(){try{return isDistributor()?"[]":pairing.allocations().toString();}catch(Exception e){return "";} }
        @JavascriptInterface public void allocatedDistribution(String admin){runOnUiThread(()->distributorScreen(admin));}
        @JavascriptInterface public synchronized String distributorLogin(String username,String password){
            try{if(!isDistributor())return "";long now=android.os.SystemClock.elapsedRealtime();if(now-lastLoginAttempt<700&&lastLoginAttempt!=0)return "";lastLoginAttempt=now;
                JSONObject u=DistributorUsers.login(store.distributorUsers(),username,password);if(u==null)return "";distributorSessionId=u.getString("id");return DistributorUsers.publicUser(u).toString();
            }catch(Exception e){return "";}
        }
        @JavascriptInterface public String distributorSession(){try{JSONObject u=sessionUser();return u==null?"":DistributorUsers.publicUser(u).toString();}catch(Exception e){return "";}}
        @JavascriptInterface public void distributorLogout(){if(isDistributor())distributorSessionId=null;}
        @JavascriptInterface public void distributorAction(String action){runOnUiThread(()->openDistributorAction(action));}
        @JavascriptInterface public void backup(){if(!isDistributor())runOnUiThread(()->askPass(true,null));}
        @JavascriptInterface public void restore(){runOnUiThread(()->{try{if(!store.role().equals("independent")){msg("الاستعادة بعد الاقتران تحتاج آلية استرداد السلطة؛ غير متاحة الآن");return;}}catch(Exception e){msg("تعذر التحقق من حالة الربط");return;}Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,RESTORE);});}
        @JavascriptInterface public void shareText(String text){if(isDistributor())return;runOnUiThread(()->{Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,text);startActivity(Intent.createChooser(i,"مشاركة الكشف"));});}
        @JavascriptInterface public void exportDocument(String name,String mime,String b64){if(isDistributor())return;document(name,mime,b64,0);}
        @JavascriptInterface public void shareDocument(String name,String mime,String b64){if(isDistributor())return;document(name,mime,b64,1);}
        @JavascriptInterface public void printDocument(String name,String b64){if(isDistributor())return;document(name,"application/pdf",b64,2);}
    }
    private boolean isDistributor(){try{return "distributor".equals(store.role());}catch(Exception e){return false;}}
    private JSONObject sessionUser()throws Exception{String id=distributorSessionId;return id==null||!isDistributor()?null:DistributorUsers.find(store.distributorUsers(),id);}
    private boolean distributorAllowed(String permission){
        try{if(DistributorUsers.allowed(sessionUser(),permission))return true;}catch(Exception ignored){}
        msg("لا توجد صلاحية لتنفيذ هذا الإجراء");return false;
    }
    private boolean syncAllowed(String id){return isDistributor()?distributorAllowed("sync"):adminAllowed(id);}
    private boolean adminAllowed(String admin){
        if(isDistributor())return distributorAllowed("admin");
        try{if(admin!=null&&store.admin(admin))return true;}catch(Exception ignored){}
        msg("يلزم حساب مدير الجهاز");return false;
    }
    private void notifyPairChange(){if(web!=null)web.evaluateJavascript("window.location.reload()",null);}
    private LinearLayout pairLayout(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setLayoutDirection(android.view.View.LAYOUT_DIRECTION_RTL);int d=(int)(18*getResources().getDisplayMetrics().density);v.setPadding(d,d,d,d);return v;}
    private void pairText(LinearLayout p,String text){TextView t=new TextView(this);t.setText(text);t.setTextSize(15);t.setPadding(0,8,0,12);p.addView(t);}
    private void pairButton(LinearLayout p,String text,Runnable action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);p.addView(b);b.setOnClickListener(v->action.run());}
    private void manageDevicesScreen(String admin){
        if(!adminAllowed(admin))return;
        try{
            JSONObject s=pairing.status();String role=s.optString("role","independent");
            LinearLayout p=pairLayout();pairText(p,"معرّف بصمة الجهاز: "+pairing.fingerprint());
            pairText(p,"مزامنة محلية تجريبية عبر Wi-Fi — بيانات وهمية فقط.");
            ScrollView scroll=new ScrollView(this);scroll.addView(p);
            AlertDialog d=new AlertDialog.Builder(this).setTitle("إدارة الجوالات").setView(scroll).setPositiveButton("إغلاق",null).create();
            if(role.equals("independent")){
                pairText(p,"هذا الجهاز مستقل الآن.");
                pairButton(p,"تعيين هذا الجهاز جوالًا أم",()->new AlertDialog.Builder(this).setTitle("تعيين الجوال الأم").setMessage("سيصبح هذا الهاتف مالك هوية الحارة؛ نقل السلطة والاستعادة على جهاز مقترن غير متاحين بعد. للتجربة ببيانات وهمية فقط.").setNegativeButton("إلغاء",null).setPositiveButton("تأكيد",(x,y)->{try{if(!adminAllowed(admin))return;pairing.becomeMother();d.dismiss();notifyPairChange();manageDevicesScreen(admin);}catch(Exception e){msg("تعذر التعيين؛ تحقق من حفظ اسم الحارة ووجود حساب مدير");}}).show());
                pairButton(p,"ربط هذا الجهاز كموزع",()->{EditText name=new EditText(this);name.setSingleLine(true);name.setHint("اسم الموزع أو الجهاز");new AlertDialog.Builder(this).setTitle("طلب الاقتران").setMessage("يجب أن تكون سجلات الأسر والدورات والتسليم فارغة في هذا الجهاز.").setView(name).setNegativeButton("إلغاء",null).setPositiveButton("إظهار QR",(x,y)->{try{if(!adminAllowed(admin))return;String packet=pairing.request(name.getText().toString());d.dismiss();notifyPairChange();showQr("طلب ربط الموزع",packet,pairing.fingerprint(), "امسح هذا الرمز بالجوال الأم وقارن البصمة.");}catch(Exception e){msg("تعذر إنشاء الطلب؛ اختر اسمًا وتأكد أن الجهاز فارغ");}}).show();});
            }else if(role.equals("pending")){
                pairText(p,"بانتظار موافقة الجوال الأم.");
                pairButton(p,"عرض طلب الربط QR",()->{try{showQr("طلب ربط الموزع",s.getString("request"),pairing.fingerprint(),"امسح الطلب بالجوال الأم وقارن البصمة.");}catch(Exception e){msg("تعذر عرض الرمز");}});
                pairButton(p,"مسح رمز موافقة الجوال الأم",()->{d.dismiss();scanQr("approval",admin);});
                pairButton(p,"إلغاء الطلب المعلق",()->new AlertDialog.Builder(this).setTitle("إلغاء الطلب؟").setMessage("إذا سبق للجوال الأم اعتماد الطلب فسيظل تسجيله هناك. لا توجد تخصيصات في هذا الإصدار.").setNegativeButton("رجوع",null).setPositiveButton("إلغاء الطلب",(x,y)->{try{if(!adminAllowed(admin))return;pairing.cancelPending();d.dismiss();notifyPairChange();}catch(Exception e){msg("تعذر الإلغاء");}}).show());
            }else if(role.equals("mother")){
                pairText(p,"الجوال الأم — "+s.getString("area"));
                pairButton(p,"مسح طلب ربط موزع جديد",()->{d.dismiss();scanQr("request",admin);});
                JSONObject devices=s.getJSONObject("devices");
                pairText(p,"الأجهزة المسجلة: "+devices.length());
                pairButton(p,"تخصيص أسر لدورة توزيع",()->{d.dismiss();chooseAllocation(admin);});
                pairButton(p,"عرض التخصيصات وحالات التسليم",()->{d.dismiss();motherAllocations(admin);});
                pairButton(p,"فتح المزامنة على الشبكة المحلية",()->{d.dismiss();chooseLan(admin);});
                for(java.util.Iterator<String> it=devices.keys();it.hasNext();){
                    JSONObject child=devices.getJSONObject(it.next());
                    pairText(p,child.getString("name")+" | "+PairPackets.shortFingerprint(child.getString("pub")));
                    String approval=child.getString("approval");pairButton(p,"عرض رمز الموافقة مجددًا",()->{try{showQr("موافقة الجوال الأم",approval,pairing.fingerprint(),"امسح الرمز من جوال الموزع وقارن بصمة الأم.");}catch(Exception e){msg("تعذر عرض الرمز");}});
                }
            }else if(role.equals("distributor")){
                pairText(p,"موزع مقترن بحارة: "+s.getString("area"));
                pairText(p,"بصمة الجوال الأم: "+PairPackets.shortFingerprint(s.getString("motherPub")));
                pairText(p,s.has("lastSync")?"آخر مزامنة: "+new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.ROOT).format(new java.util.Date(s.getLong("lastSync"))):"لم تتم المزامنة بعد.");
                pairButton(p,"مزامنة بمسح رمز QR للجوال الأم",()->{d.dismiss();scanQr("sync",admin);});
                pairButton(p,"إدخال العنوان يدويًا (احتياطي)",()->{d.dismiss();connectLan(admin);});
                pairButton(p,"تسجيل توزيع الأسر المخصصة",()->{d.dismiss();distributorScreen(admin);});
            }
            d.show();
        }catch(Exception e){msg("تعذر فتح إدارة الجوالات؛ لم يتم تغيير بياناتك");}
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
            JSONObject state=pairing.status(),data=new JSONObject(store.readState()),c=SyncRules.find(data.getJSONArray("cycles"),cycle),grants=SyncRules.map(state,"grants");
            java.util.ArrayList<JSONObject> families=new java.util.ArrayList<>();JSONArray all=data.getJSONArray("families");
            for(int i=0;i<all.length();i++){JSONObject f=all.getJSONObject(i);String id=f.getString("id");if(!grants.has(SyncRules.key(cycle,id))&&SyncRules.delivery(data,cycle,id)==null)families.add(f);}
            if(families.isEmpty()){msg("لا توجد أسر متاحة للتخصيص في هذه الدورة");return;}
            java.util.ArrayList<String> selected=new java.util.ArrayList<>();
            LinearLayout p=pairLayout();pairText(p,"الحد لكل أسرة: "+c.getInt("quota")+" أسطوانة. لا يمكن نقل التخصيص إلى موزع آخر في هذه النسخة.");
            EditText search=new EditText(this);search.setSingleLine(true);search.setHint("بحث باسم الأسرة أو رقم المنزل");p.addView(search);
            LinearLayout rows=pairLayout();ScrollView scroll=new ScrollView(this);scroll.addView(rows);p.addView(scroll,new LinearLayout.LayoutParams(-1,(int)(300*getResources().getDisplayMetrics().density)));
            Runnable render=()->{rows.removeAllViews();String q=search.getText().toString().trim();for(JSONObject f:families){String id=f.optString("id"),label=f.optInt("no")+" · "+f.optString("name")+" — منزل "+f.optString("house");if(!q.isEmpty()&&!label.contains(q))continue;
                CheckBox check=new CheckBox(this);check.setText(label);check.setChecked(selected.contains(id));check.setOnCheckedChangeListener((v,on)->{if(on){if(!selected.contains(id))selected.add(id);}else selected.remove(id);});rows.addView(check);}};
            search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){render.run();}public void afterTextChanged(android.text.Editable e){}});
            render.run();
            AlertDialog dlg=new AlertDialog.Builder(this).setTitle("حدد الأسر المطلوبة").setView(p).setNegativeButton("إلغاء",null).setPositiveButton("اعتماد التخصيص",null).create();
            dlg.setOnShowListener(v->dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{if(selected.isEmpty()){msg("حدد أسرة واحدة على الأقل");return;}new AlertDialog.Builder(this).setTitle("تأكيد تخصيص "+selected.size()+" أسرة؟").setMessage("ستحجز هذه الأسر لهذا الموزع حصريًا في الدورة المحددة.").setNegativeButton("رجوع",null).setPositiveButton("تأكيد",(x,y)->{try{if(!adminAllowed(admin))return;pairing.allocate(child,cycle,selected);dlg.dismiss();notifyPairChange();msg("تم حفظ التخصيصات؛ بانتظار المزامنة");}catch(Exception e){msg("تعذر التخصيص؛ قد تكون الأسرة حُجزت أو استلمت بالفعل");}}).show();}));dlg.show();
        }catch(Exception e){msg("تعذر فتح قائمة الأسر");}
    }
    private void motherAllocations(String admin){
        if(!adminAllowed(admin))return;
        try{
            JSONArray rows=pairing.allocations();LinearLayout p=pairLayout();pairText(p,"التخصيصات: "+rows.length());
            for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);pairText(p,r.getString("cycleTitle")+" | "+r.getString("name")+" | "+r.getString("distributorName")+" — "+(r.getBoolean("done")?"تم التسليم والمزامنة":"بانتظار التسليم"));}
            ScrollView sc=new ScrollView(this);sc.addView(p);new AlertDialog.Builder(this).setTitle("حالة التخصيصات").setView(sc).setPositiveButton("إغلاق",null).show();
        }catch(Exception e){msg("تعذر عرض التخصيصات");}
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
        if(!r.optBoolean("done"))return "لم يستلم";
        String at=r.optString("at","").replace('T',' ').replace("Z","");
        if(at.length()>16)at=at.substring(0,16);
        String op=r.optString("operator","");
        return "تم التسليم: "+r.optInt("q")+" | "+(r.optBoolean("synced")?"تمت المزامنة":"بانتظار المزامنة")+(at.isEmpty()?"":"\n"+at)+(op.isEmpty()?"":" | "+op);
    }
    private void readDistributorRows(boolean history){
        try{JSONArray all=pairing.allocations();LinearLayout p=pairLayout();EditText search=new EditText(this);search.setSingleLine(true);search.setHint("البحث بالاسم أو المنزل أو الدورة");p.addView(search);
            LinearLayout list=pairLayout();ScrollView scroll=new ScrollView(this);scroll.addView(list);p.addView(scroll,new LinearLayout.LayoutParams(-1,(int)(380*getResources().getDisplayMetrics().density)));
            Runnable draw=()->{list.removeAllViews();int count=0;String query=DistributorUsers.normalize(search.getText().toString());for(int i=0;i<all.length();i++){JSONObject r=all.optJSONObject(i);if(r==null||(history&&!r.optBoolean("done")))continue;
                String label=r.optInt("no")+" · "+r.optString("name")+" | "+r.optString("house")+" | "+r.optString("cycleTitle");if(!DistributorUsers.normalize(label).contains(query))continue;count++;pairText(list,label+"\n"+deliveryDescription(r));}
                if(count==0)pairText(list,history?"لا توجد حركات تسليم مطابقة":"لا توجد أسر مطابقة");};
            search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){draw.run();}public void afterTextChanged(android.text.Editable e){}});draw.run();
            new AlertDialog.Builder(this).setTitle(history?"سجل التسليم":"الأسر المخصصة").setView(p).setPositiveButton("إغلاق",null).show();
        }catch(Exception e){msg("تعذر قراءة السجل");}
    }
    private void distributorReports(){
        try{JSONArray all=pairing.allocations();java.util.LinkedHashMap<String,int[]> sums=new java.util.LinkedHashMap<>();java.util.LinkedHashMap<String,String> labels=new java.util.LinkedHashMap<>();
            for(int i=0;i<all.length();i++){JSONObject r=all.getJSONObject(i);String cycle=r.getString("cycle");labels.put(cycle,r.getString("cycleTitle")+" · "+r.optString("cycleDate"));int[] n=sums.get(cycle);if(n==null){n=new int[5];sums.put(cycle,n);}n[0]++;if(r.optBoolean("done")){n[1]++;n[2]+=r.optInt("q");if(!r.optBoolean("synced"))n[3]++;}else n[4]++;}
            StringBuilder out=new StringBuilder("كشف توزيع الغاز — "+pairing.status().getString("area"));
            for(String cycle:sums.keySet()){int[] n=sums.get(cycle);out.append("\n\n").append(labels.get(cycle)).append("\nالأسر المخصصة: ").append(n[0]).append(" | المستلمة: ").append(n[1]).append(" | المتبقية: ").append(n[4]).append("\nالأسطوانات المسلمة: ").append(n[2]).append(" | حركات بانتظار المزامنة: ").append(n[3]);}
            if(sums.isEmpty())out.append("\nلا توجد تخصيصات");final String text=out.toString();
            ScrollView sc=new ScrollView(this);LinearLayout p=pairLayout();pairText(p,text);sc.addView(p);
            AlertDialog.Builder d=new AlertDialog.Builder(this).setTitle("الكشوف والتقارير").setView(sc).setPositiveButton("إغلاق",null);
            if(DistributorUsers.allowed(sessionUser(),"share"))d.setNeutralButton("مشاركة الكشف",(v,w)->{if(!distributorAllowed("share")||!distributorAllowed("reports"))return;Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,text);startActivity(Intent.createChooser(i,"مشاركة الكشف"));});d.show();
        }catch(Exception e){msg("تعذر إعداد التقرير");}
    }
    private void distributorSettings(){
        try{JSONObject user=sessionUser();if(user==null)return;
            JSONObject s=pairing.status();LinearLayout p=pairLayout();pairText(p,"الحارة: "+s.getString("area")+"\nالمستخدم: "+user.getString("username")+"\n"+(s.has("lastSync")?"آخر مزامنة: "+new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.ROOT).format(new java.util.Date(s.getLong("lastSync"))):"لم تتم المزامنة بعد"));
            pairText(p,"بصمة الجوال الأم: "+PairPackets.shortFingerprint(s.getString("motherPub")));
            AlertDialog d=new AlertDialog.Builder(this).setTitle("إعدادات الموزع").setView(p).setPositiveButton("إغلاق",null).create();
            if(DistributorUsers.allowed(user,"admin"))pairButton(p,"المستخدمون والصلاحيات",()->{d.dismiss();distributorUserList();});
            if(DistributorUsers.allowed(user,"sync"))pairButton(p,"المزامنة بعنوان IP (احتياطي)",()->{d.dismiss();connectLan(user.optString("id"));});
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
    private void distributorScreen(String admin){
        if(!distributorAllowed("deliver"))return;
        try{
            PairPackets.require("distributor".equals(store.role()),"Distributor required");JSONArray rows=pairing.allocations();
            LinearLayout p=pairLayout();pairText(p,"الأسر المخصصة: "+rows.length());
            ScrollView sc=new ScrollView(this);sc.addView(p);AlertDialog dlg=new AlertDialog.Builder(this).setTitle("تسجيل التوزيع").setView(sc).setPositiveButton("إغلاق",null).create();
            if(rows.length()==0)pairText(p,"لا توجد أسر مخصصة بعد. نفّذ المزامنة أولًا.");
            for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);
                pairText(p,r.getString("cycleTitle")+" | "+r.getInt("no")+" · "+r.getString("name")+" | منزل "+r.getString("house")+" | الحد "+r.getInt("maxQty"));
                if(r.getBoolean("done"))pairText(p,r.getBoolean("synced")?"تم التسليم — تمت المزامنة":"تم التسليم — بانتظار المزامنة");
                else pairButton(p,"تسجيل التسليم",()->{dlg.dismiss();recordDelivery(admin,r);});
            }
            dlg.show();
        }catch(Exception e){msg("تعذر فتح الأسر المخصصة");}
    }
    private void recordDelivery(String admin,JSONObject row){
        if(!distributorAllowed("deliver"))return;
        EditText qty=new EditText(this);qty.setInputType(InputType.TYPE_CLASS_NUMBER);qty.setText(""+row.optInt("maxQty"));
        new AlertDialog.Builder(this).setTitle("تسليم "+row.optString("name")).setMessage("الكمية لا تتجاوز "+row.optInt("maxQty")+" أسطوانة. بعد الحفظ لا يمكن تكرار الحركة أو تعديلها في هذه النسخة.").setView(qty).setNegativeButton("إلغاء",null).setPositiveButton("تأكيد التسليم",(d,w)->{try{if(!distributorAllowed("deliver"))return;JSONObject actor=sessionUser();int n=Integer.parseInt(qty.getText().toString());pairing.record(row.getString("key"),n,actor.getString("id"),actor.getString("username"));msg("تم حفظ التسليم محليًا");distributorScreen(admin);}catch(Exception e){msg("لم يتم الحفظ؛ تحقق من الكمية أو التسليم السابق");distributorScreen(admin);}}).show();
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
        EditText ip=new EditText(this);ip.setSingleLine(true);ip.setInputType(InputType.TYPE_CLASS_TEXT);ip.setHint("192.168.x.x");
        new AlertDialog.Builder(this).setTitle("المزامنة مع الجوال الأم").setMessage("أدخل عنوان Wi-Fi المعروض على شاشة مزامنة الأم.").setView(ip).setNegativeButton("إلغاء",null).setPositiveButton("مزامنة",(d,w)->{
            syncAddress(admin,ip.getText().toString().trim());
        }).show();
    }
    private void syncAddress(String admin,String ip){
        if(!syncAllowed(admin))return;
        ProgressDialog busy=ProgressDialog.show(this,"المزامنة","جارٍ التحقق وتبادل البيانات…",true,false);
        network.execute(()->{try{pairing.synchronize(this,ip);runOnUiThread(()->{busy.dismiss();notifyPairChange();msg("تمت المزامنة بنجاح");});}
            catch(Exception e){runOnUiThread(()->{busy.dismiss();new AlertDialog.Builder(this).setTitle("لم تكتمل المزامنة").setMessage(GazLan.diagnostic(e)+"\nحركاتك محفوظة. أرسل صورة هذه الرسالة عند تكرار المشكلة.").setPositiveButton("إغلاق",null).show();});}});
    }
    @Override protected void onPause(){closeLan();super.onPause();}
    private void scanQr(String mode,String admin){
        if("sync".equals(mode)?!syncAllowed(admin):!adminAllowed(admin))return;
        scanMode=mode;scanAdmin=admin;
        new IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).setCaptureActivity(PortraitCaptureActivity.class).setPrompt("وجّه الكاميرا إلى رمز QR").setBeepEnabled(false).setOrientationLocked(true).initiateScan();
    }
    private void handlePairScan(String mode,String admin,String packet){
        if("sync".equals(mode)?!syncAllowed(admin):!adminAllowed(admin))return;
        try{
            if(mode.equals("sync")){String ip=pairing.readSyncQr(packet);syncAddress(admin,ip);}
            else if(mode.equals("request")){
                JSONObject r=PairPackets.unpack(packet,"request");String fp=PairPackets.shortFingerprint(r.getString("pub"));
                new AlertDialog.Builder(this).setTitle("قبول الموزع؟").setMessage("الاسم: "+r.getString("name")+"\nالبصمة: "+fp+"\nقارن البصمة بالتي تظهر على جوال الموزع نفسه. لا توافق إن اختلفت.").setNegativeButton("إلغاء",null).setPositiveButton("البصمة متطابقة",(d,w)->{try{if(!adminAllowed(admin))return;String approval=pairing.approve(packet);notifyPairChange();showQr("موافقة الجوال الأم",approval,pairing.fingerprint(),"امسح هذا الرمز من جهاز الموزع وقارن بصمة الأم.");}catch(Exception e){msg("رفض الطلب؛ تعارض أو جهاز مسجل سابقًا");}}).show();
            }else if(mode.equals("approval")){
                JSONObject r=pairing.inspectApproval(packet);String fp=PairPackets.shortFingerprint(r.getString("pub"));
                new AlertDialog.Builder(this).setTitle("تأكيد الجوال الأم").setMessage("الحارة: "+r.getString("area")+"\nبصمة الأم: "+fp+"\nقارنها بالبصمة المعروضة على الجوال الأم.").setNegativeButton("إلغاء",null).setPositiveButton("البصمة متطابقة",(d,w)->{try{if(!adminAllowed(admin))return;pairing.accept(packet);notifyPairChange();msg("تم اعتماد هوية الجوال الأم؛ الصرف مقفل حتى التخصيص");}catch(Exception e){msg("تعذر اعتماد الموافقة");}}).show();
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
    private void askPass(boolean backup,Uri uri){
        EditText password=new EditText(this);password.setSingleLine(true);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);password.setHint("كلمة مرور النسخة (8 أحرف فأكثر)");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(backup?"تشفير النسخة الاحتياطية":"فك تشفير النسخة الاحتياطية").setMessage(backup?"احتفظ بكلمة المرور خارج هذا الجوال. لا يمكن استردادها.":"لن تتغير البيانات قبل التحقق والتأكيد.").setView(password).setNegativeButton("إلغاء",null).setPositiveButton("متابعة",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{char[] pass=password.getText().toString().toCharArray();if(pass.length<8){Arrays.fill(pass,'\0');password.setError("8 أحرف على الأقل");return;}password.setText("");dialog.dismiss();
            workers.execute(()->{try{if(backup){byte[] encrypted=store.backup(pass);File f=tempFile("gaz_backup_"+System.currentTimeMillis(),".json.gz",encrypted);runOnUiThread(()->savePicker(f,"application/gzip"));}else{
                byte[] bytes;try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException();bytes=readLimited(in);}
                String json=GazStore.decodeBackup(bytes,pass);
                runOnUiThread(()->new AlertDialog.Builder(this).setTitle("استبدال البيانات المحلية؟").setMessage("النسخة صحيحة. ستستبدل جميع البيانات والحسابات على هذا الجهاز. يوصى بحفظ نسخة احتياطية أولًا.").setNegativeButton("إلغاء",null).setPositiveButton("استعادة",(d,b)->workers.execute(()->{try{store.restoreIndependent(json);runOnUiThread(()->web.evaluateJavascript("window.dispatchEvent(new Event('gaz-restore-complete'))",null));msg("تمت الاستعادة");}catch(Exception e){msg("فشلت الاستعادة؛ لم تُعتمد بيانات جديدة");}})).show());
            }}catch(Exception e){msg(backup?"تعذر إنشاء النسخة الاحتياطية":"تعذر فتح النسخة؛ تحقق من الملف وكلمة المرور");}finally{Arrays.fill(pass,'\0');}});
        }));dialog.show();
    }
    private static byte[] readLimited(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(out.size()+n>GazStore.MAX)throw new IOException("Too large");out.write(b,0,n);}return out.toByteArray();}
    @Override protected void onActivityResult(int req,int result,Intent data){
        IntentResult scan=IntentIntegrator.parseActivityResult(req,result,data);
        if(scan!=null){String mode=scanMode,admin=scanAdmin;scanMode=null;scanAdmin=null;if(scan.getContents()!=null&&mode!=null&&admin!=null)handlePairScan(mode,admin,scan.getContents());return;}
        super.onActivityResult(req,result,data);
        if(req==SAVE){File f=pending;pending=null;if(result==RESULT_OK&&data!=null&&data.getData()!=null&&f!=null){
            Uri uri=data.getData();workers.execute(()->{try(InputStream in=new FileInputStream(f);OutputStream out=getContentResolver().openOutputStream(uri,"w")){if(out==null)throw new IOException();byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.flush();msg("تم حفظ الملف");}catch(Exception e){msg("تعذر حفظ الملف");}});
        }}else if(req==RESTORE&&result==RESULT_OK&&data!=null&&data.getData()!=null)askPass(false,data.getData());
    }
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);if(pending!=null)b.putString("pending",pending.getAbsolutePath());b.putString("scanMode",scanMode);b.putString("scanAdmin",scanAdmin);}
    private void msg(String s){runOnUiThread(()->Toast.makeText(this,s,Toast.LENGTH_LONG).show());}
    @Override public void onBackPressed(){if(web!=null){web.evaluateJavascript("(function(){let p=document.getElementById('gazPreview');if(p&&!p.hidden){document.getElementById('gazPreviewClose').click();return;}if(typeof activeUser!=='undefined'&&activeUser&&current!=='home'){show('home');return;}alert('للخروج استخدم شاشة التطبيقات أو تسجيل الخروج.');})()",null);}else super.onBackPressed();}
    @Override protected void onDestroy(){if(web!=null){web.removeJavascriptInterface("GazNative");web.destroy();}closeLan();network.shutdownNow();workers.shutdown();super.onDestroy();}
}
