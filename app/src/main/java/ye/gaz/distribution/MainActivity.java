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

public final class MainActivity extends Activity {
    private static final String HOME="https://appassets.androidplatform.net/assets/index.html";
    private static final int SAVE=21,RESTORE=22;
    private WebView web;
    private GazStore store;
    private File pending;
    private final ExecutorService workers=Executors.newSingleThreadExecutor();
    private String device;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        try{store=new GazStore(this);store.readState();}catch(Exception e){new AlertDialog.Builder(this).setTitle("تعذر فتح البيانات").setMessage("لم تُحذف بياناتك. راجع الدعم الفني.").setPositiveButton("إغلاق",(d,w)->finish()).setCancelable(false).show();return;}
        android.content.SharedPreferences prefs=getSharedPreferences("identity",MODE_PRIVATE);
        device=prefs.getString("id",null);if(device==null){device=UUID.randomUUID().toString();prefs.edit().putString("id",device).commit();}
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
        @JavascriptInterface public String readState(){try{return store.readState();}catch(Exception e){return "";}}
        @JavascriptInterface public boolean writeState(String json){try{store.writeState(json);return true;}catch(Exception e){return false;}}
        @JavascriptInterface public String deviceId(){return device;}
        @JavascriptInterface public void backup(){runOnUiThread(()->askPass(true,null));}
        @JavascriptInterface public void restore(){runOnUiThread(()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,RESTORE);});}
        @JavascriptInterface public void shareText(String text){runOnUiThread(()->{Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,text);startActivity(Intent.createChooser(i,"مشاركة الكشف"));});}
        @JavascriptInterface public void exportDocument(String name,String mime,String b64){document(name,mime,b64,0);}
        @JavascriptInterface public void shareDocument(String name,String mime,String b64){document(name,mime,b64,1);}
        @JavascriptInterface public void printDocument(String name,String b64){document(name,"application/pdf",b64,2);}
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
            workers.execute(()->{try{if(backup){byte[] encrypted=store.backup(pass);File f=tempFile("gaz_backup_"+System.currentTimeMillis(),".gazbak",encrypted);runOnUiThread(()->savePicker(f,"application/octet-stream"));}else{
                byte[] bytes;try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException();bytes=readLimited(in);}
                String json=GazStore.decodeBackup(bytes,pass);
                runOnUiThread(()->new AlertDialog.Builder(this).setTitle("استبدال البيانات المحلية؟").setMessage("النسخة صحيحة. ستستبدل جميع البيانات والحسابات على هذا الجهاز. يوصى بحفظ نسخة احتياطية أولًا.").setNegativeButton("إلغاء",null).setPositiveButton("استعادة",(d,b)->workers.execute(()->{try{store.writeState(json);runOnUiThread(()->web.evaluateJavascript("window.dispatchEvent(new Event('gaz-restore-complete'))",null));msg("تمت الاستعادة");}catch(Exception e){msg("فشلت الاستعادة؛ لم تُعتمد بيانات جديدة");}})).show());
            }}catch(Exception e){msg(backup?"تعذر إنشاء النسخة الاحتياطية":"تعذر فتح النسخة؛ تحقق من الملف وكلمة المرور");}finally{Arrays.fill(pass,'\0');}});
        }));dialog.show();
    }
    private static byte[] readLimited(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(out.size()+n>GazStore.MAX)throw new IOException("Too large");out.write(b,0,n);}return out.toByteArray();}
    @Override protected void onActivityResult(int req,int result,Intent data){
        super.onActivityResult(req,result,data);
        if(req==SAVE){File f=pending;pending=null;if(result==RESULT_OK&&data!=null&&data.getData()!=null&&f!=null){
            Uri uri=data.getData();workers.execute(()->{try(InputStream in=new FileInputStream(f);OutputStream out=getContentResolver().openOutputStream(uri,"w")){if(out==null)throw new IOException();byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.flush();msg("تم حفظ الملف");}catch(Exception e){msg("تعذر حفظ الملف");}});
        }}else if(req==RESTORE&&result==RESULT_OK&&data!=null&&data.getData()!=null)askPass(false,data.getData());
    }
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);if(pending!=null)b.putString("pending",pending.getAbsolutePath());}
    private void msg(String s){runOnUiThread(()->Toast.makeText(this,s,Toast.LENGTH_LONG).show());}
    @Override public void onBackPressed(){if(web!=null){web.evaluateJavascript("(function(){let p=document.getElementById('gazPreview');if(p&&!p.hidden){document.getElementById('gazPreviewClose').click();return;}if(typeof activeUser!=='undefined'&&activeUser&&current!=='home'){show('home');return;}alert('للخروج استخدم شاشة التطبيقات أو تسجيل الخروج.');})()",null);}else super.onBackPressed();}
    @Override protected void onDestroy(){if(web!=null){web.removeJavascriptInterface("GazNative");web.destroy();}workers.shutdown();super.onDestroy();}
}
