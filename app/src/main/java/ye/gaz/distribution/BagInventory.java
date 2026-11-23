package ye.gaz.distribution;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;

/**
 * Per-bag inventory: delivered quantities + exclusive grants that may still be
 * redeemed offline. Returned grants release their unused reservation.
 * Missing stockTotal is permitted only for pre-upgrade legacy data; new grants
 * must have a configured total.
 */
final class BagInventory {
    private BagInventory(){}

    static Integer limit(JSONObject cycle){
        Object raw=cycle.opt("stockTotal");
        if(raw==null||raw==JSONObject.NULL||"".equals(raw))return null;
        long n=integer(raw,1,1_000_000,"إجمالي عطايا الحقيبة");
        return (int)n;
    }
    static int warning(JSONObject cycle){
        Object raw=cycle.opt("stockWarningAt");
        return raw==null||raw==JSONObject.NULL||"".equals(raw)?0:(int)integer(raw,0,1_000_000,"حد تنبيه نفاد العطايا");
    }
    static long integer(Object raw,long min,long max,String label){
        String s=String.valueOf(raw).trim();
        if(!s.matches("[0-9]{1,12}"))throw new IllegalArgumentException(label+" يجب أن يكون عددًا صحيحًا");
        long n;
        try{n=Long.parseLong(s);}catch(NumberFormatException e){throw new IllegalArgumentException(label+" خارج الحدود");}
        if(n<min||n>max)throw new IllegalArgumentException(label+" خارج الحدود");
        return n;
    }

    static final class Balance{
        final Integer total;
        final long delivered,reserved,available;
        Balance(Integer total,long delivered,long reserved){
            this.total=total;this.delivered=delivered;this.reserved=reserved;
            this.available=total==null?-1:(long)total-delivered-reserved;
        }
    }

    static Balance balance(JSONObject local,JSONObject paired,String cycle)throws Exception{
        JSONObject c=SyncRules.find(local.getJSONArray("cycles"),cycle);
        if(c==null)throw new IllegalArgumentException("الحقيبة غير موجودة");
        Integer total=limit(c);
        int warn=warning(c);
        if(total!=null&&warn>total)throw new IllegalArgumentException("حد التنبيه يتجاوز إجمالي عطايا الحقيبة");
        long delivered=0,reserved=0;
        JSONArray movements=local.getJSONArray("deliveries");
        java.util.HashSet<String> deliveredFamilies=new java.util.HashSet<>();
        for(int i=0;i<movements.length();i++){
            JSONObject d=movements.getJSONObject(i);
            if(!cycle.equals(d.getString("c")))continue;
            long q=integer(d.get("q"),1,1_000_000_000,"كمية التسليم");
            delivered=Math.addExact(delivered,q);
            deliveredFamilies.add(d.getString("f"));
        }
        if(paired!=null&&"mother".equals(paired.optString("role",""))){
            JSONObject grants=SyncRules.map(paired,"grants");
            for(Iterator<String> it=grants.keys();it.hasNext();){
                String key=it.next();JSONObject grant=SyncWire.peek(grants.getString(key));
                if(!cycle.equals(grant.getString("cycle"))||deliveredFamilies.contains(grant.getString("family")))continue;
                JSONObject ctl=MotherDelivery.control(paired,key);
                if(ctl!=null&&ctl.optBoolean("returned"))continue;
                reserved=Math.addExact(reserved,integer(grant.get("maxQty"),1,10_000,"كمية الحجز"));
            }
        }
        return new Balance(total,delivered,reserved);
    }

    static void requireAvailable(JSONObject local,JSONObject paired,String cycle,long extra)throws Exception{
        Balance b=balance(local,paired,cycle);
        if(b.total==null)throw new IllegalStateException("حدد إجمالي عطايا الحقيبة أولًا");
        if(extra>b.available)throw new IllegalStateException(
            "رصيد الحقيبة لا يكفي؛ المتاح "+Math.max(0,b.available)+" عطية فقط");
    }

    static void verify(JSONObject local,JSONObject paired)throws Exception{
        JSONArray cycles=local.getJSONArray("cycles");
        for(int i=0;i<cycles.length();i++){
            JSONObject c=cycles.getJSONObject(i);
            Balance b=balance(local,paired,c.getString("id"));
            if(b.total!=null&&b.available<0)throw new IllegalStateException(
                "تجاوز رصيد الحقيبة: "+c.optString("title","")+
                " — الإجمالي "+b.total+"، المصروف "+b.delivered+"، المحجوز "+b.reserved);
        }
    }
}
