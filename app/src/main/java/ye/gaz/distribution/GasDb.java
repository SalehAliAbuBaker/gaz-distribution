package ye.gaz.distribution;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.UUID;

final class GasDb extends SQLiteOpenHelper {
    GasDb(Context c) { super(c, "gas-local.db", null, 1); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE family(id TEXT PRIMARY KEY, code TEXT NOT NULL UNIQUE, name TEXT NOT NULL, members INTEGER NOT NULL CHECK(members>0))");
        db.execSQL("CREATE TABLE cycle(id TEXT PRIMARY KEY, title TEXT NOT NULL, opened INTEGER NOT NULL, closed INTEGER)");
        db.execSQL("CREATE UNIQUE INDEX one_open_cycle ON cycle((1)) WHERE closed IS NULL");
        db.execSQL("CREATE TABLE delivery(id TEXT PRIMARY KEY, cycle_id TEXT NOT NULL REFERENCES cycle(id), family_id TEXT NOT NULL REFERENCES family(id), quantity INTEGER NOT NULL CHECK(quantity>0), created INTEGER NOT NULL, UNIQUE(cycle_id,family_id))");
        db.execSQL("CREATE TRIGGER delivery_closed BEFORE INSERT ON delivery WHEN NOT EXISTS (SELECT 1 FROM cycle WHERE id=NEW.cycle_id AND closed IS NULL) BEGIN SELECT RAISE(ABORT,'cycle not open'); END");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int a,int b) { throw new IllegalStateException("Migration required"); }
    private static String id(){return UUID.randomUUID().toString();}
    void addFamily(String code,String name,int members) {
        if(code.trim().isEmpty()||name.trim().isEmpty()||members<1) throw new IllegalArgumentException();
        ContentValues v=new ContentValues(); v.put("id",id()); v.put("code",code.trim()); v.put("name",name.trim()); v.put("members",members);
        getWritableDatabase().insertOrThrow("family",null,v);
    }
    void openCycle(String title){
        if(title.trim().isEmpty()) throw new IllegalArgumentException();
        ContentValues v=new ContentValues(); v.put("id",id()); v.put("title",title.trim()); v.put("opened",System.currentTimeMillis()); getWritableDatabase().insertOrThrow("cycle",null,v);
    }
    String[] activeCycle(){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,title FROM cycle WHERE closed IS NULL",null)) {return c.moveToFirst()?new String[]{c.getString(0),c.getString(1)}:null;}
    }
    void closeCycle(String cycle){
        ContentValues v=new ContentValues(); v.put("closed",System.currentTimeMillis()); getWritableDatabase().update("cycle",v,"id=? AND closed IS NULL",new String[]{cycle});
    }
    void deliver(String cycle,String family,int qty){
        if(qty<1) throw new IllegalArgumentException();
        ContentValues v=new ContentValues(); v.put("id",id()); v.put("cycle_id",cycle); v.put("family_id",family); v.put("quantity",qty); v.put("created",System.currentTimeMillis()); getWritableDatabase().insertOrThrow("delivery",null,v);
    }
    Cursor families(String query,String cycle){
        return getReadableDatabase().rawQuery("SELECT f.id,f.code,f.name,f.members,d.quantity FROM family f LEFT JOIN delivery d ON d.family_id=f.id AND d.cycle_id=? WHERE instr(f.name,?)>0 OR instr(f.code,?)>0 ORDER BY f.name",new String[]{cycle==null?"":cycle,query,query});
    }
    Cursor history(){
        return getReadableDatabase().rawQuery("SELECT c.title, COUNT(d.id), COALESCE(SUM(d.quantity),0), c.closed FROM cycle c LEFT JOIN delivery d ON c.id=d.cycle_id GROUP BY c.id ORDER BY c.opened DESC",null);
    }
}
