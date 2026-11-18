package ye.gaz.distribution;
import android.app.*;
import android.os.Bundle;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.widget.*;
import android.text.*;

public final class MainActivity extends Activity {
    private GasDb db;
    private SharedPreferences prefs;
    private LinearLayout root, rows;
    private EditText search;
    private String[] cycle;
    private static final int GREEN=Color.rgb(20,92,77);
    @Override public void onCreate(Bundle s) {
        super.onCreate(s); db=new GasDb(this); prefs=getSharedPreferences("settings",MODE_PRIVATE);
        if(!prefs.contains("area")) setup(); else home();
    }
    private int dp(int p){return Math.round(p*getResources().getDisplayMetrics().density);}
    private void page(){
        ScrollView scroll=new ScrollView(this); root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); root.setPadding(dp(18),dp(18),dp(18),dp(18)); root.setBackgroundColor(Color.rgb(247,250,248));
        scroll.addView(root); setContentView(scroll);
    }
    private TextView label(LinearLayout parent,String s,int size){
        TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(GREEN); t.setPadding(0,dp(9),0,dp(6)); parent.addView(t); return t;
    }
    private void button(LinearLayout parent,String s,Runnable action){
        Button b=new Button(this); b.setText(s); b.setAllCaps(false); parent.addView(b); b.setOnClickListener(v->action.run());
    }
    private EditText field(LinearLayout parent,String hint,boolean numeric){
        EditText e=new EditText(this); e.setHint(hint); e.setSingleLine(true);
        if(numeric)e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        parent.addView(e); return e;
    }
    private void msg(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void setup(){
        page(); label(root,"توزيع الغاز",26); label(root,"إعداد الحارة • نسخة محلية تجريبية 0.1.0",17);
        EditText area=field(root,"اسم الحارة",false); area.setText("حارة الروضة");
        label(root,"للاختبار ببيانات وهمية فقط. ربط الموزعين والنسخ الاحتياطي غير متاحين بعد.",15);
        button(root,"إنشاء المنظومة المحلية",()->{String s=area.getText().toString().trim(); if(s.isEmpty()){msg("أدخل اسم الحارة");return;} prefs.edit().putString("area",s).apply();home();});
    }
    private void home(){
        page(); cycle=db.activeCycle();
        label(root,"توزيع الغاز – "+prefs.getString("area",""),24);
        label(root,"0.1.0 | اختبار محلي على جهاز واحد فقط",14);
        label(root,cycle==null?"لا توجد دورة توزيع مفتوحة":"الدورة الحالية: "+cycle[1],19);
        button(root,"إضافة أسرة",this::addFamily);
        if(cycle==null) button(root,"فتح دورة توزيع",this::openCycle); else button(root,"إغلاق الدورة",this::confirmClose);
        button(root,"سجل الدورات والإحصائيات",this::history);
        search=field(root,"ابحث باسم الأسرة أو رقمها",false);
        rows=new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL); root.addView(rows);
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int a,int c,int f){}
            public void onTextChanged(CharSequence s,int a,int b,int c){render();}
            public void afterTextChanged(Editable e){}
        });
        render();
    }
    private void render(){
        rows.removeAllViews(); int n=0;
        try(Cursor c=db.families(search.getText().toString().trim(),cycle==null?null:cycle[0])){
            while(c.moveToNext()){
                n++; String id=c.getString(0),name=c.getString(2); boolean delivered=!c.isNull(4);
                label(rows,c.getString(1)+" — "+name+" | أفراد: "+c.getInt(3),17);
                if(cycle!=null) {
                    if(delivered) label(rows,"تم التسليم: "+c.getInt(4),14);
                    else button(rows,"تسجيل تسليم الغاز",()->deliver(id,name));
                }
            }
        }
        if(n==0)label(rows,"لا توجد أسر مطابقة",15);
    }
    private LinearLayout form(){
        LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);p.setPadding(dp(20),dp(6),dp(20),dp(6));return p;
    }
    private void dialog(String title,LinearLayout p,Runnable save){
        AlertDialog d=new AlertDialog.Builder(this).setTitle(title).setView(p).setNegativeButton("إلغاء",null).setPositiveButton("حفظ",null).create();
        d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
            try{save.run();d.dismiss();home();}catch(SQLiteConstraintException ex){msg("تعذر الحفظ: سجل مكرر أو دورة مغلقة");}catch(IllegalArgumentException ex){msg("تحقق من الحقول المطلوبة والعدد");}
        }));d.show();
    }
    private void addFamily(){
        LinearLayout p=form(); EditText code=field(p,"رقم الأسرة",false),name=field(p,"اسم رب الأسرة",false),members=field(p,"عدد الأفراد",true);
        dialog("إضافة أسرة",p,()->db.addFamily(code.getText().toString(),name.getText().toString(),Integer.parseInt(members.getText().toString())));
    }
    private void openCycle(){LinearLayout p=form();EditText title=field(p,"اسم الدورة",false);dialog("فتح دورة",p,()->db.openCycle(title.getText().toString()));}
    private void deliver(String id,String name){
        String selected=cycle[0]; LinearLayout p=form();label(p,name,17);EditText qty=field(p,"عدد الأسطوانات",true);qty.setText("1");
        dialog("تأكيد تسليم الغاز",p,()->db.deliver(selected,id,Integer.parseInt(qty.getText().toString())));
    }
    private void confirmClose(){String id=cycle[0];new AlertDialog.Builder(this).setTitle("إغلاق دورة التوزيع؟").setMessage("لن يمكن إضافة صرف إلى الدورة المغلقة.").setNegativeButton("إلغاء",null).setPositiveButton("إغلاق",(d,w)->{db.closeCycle(id);home();}).show();}
    private void history(){
        page();label(root,"سجل دورات التوزيع",24);button(root,"رجوع",this::home);
        try(Cursor c=db.history()){while(c.moveToNext()){label(root,c.getString(0)+(c.isNull(3)?" • مفتوحة":" • مغلقة"),18);label(root,"الأسر المستلمة: "+c.getInt(1)+" | الأسطوانات: "+c.getInt(2),16);}}
    }
    @Override protected void onDestroy(){if(db!=null)db.close();super.onDestroy();}
}
