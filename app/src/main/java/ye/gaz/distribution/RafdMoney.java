package ye.gaz.distribution;
import org.json.*;
/** Currency amounts are stored in exact 1/100 units, never floating point. */
final class RafdMoney {
    static final long MAX_UNIT_MINOR=100_000_000_000L;
    private RafdMoney(){}
    static String kind(JSONObject j)throws Exception {
        String k=j.optString("valueKind","none");
        if(!k.equals("none")&&!k.equals("cash")&&!k.equals("estimated"))throw new JSONException("Invalid value kind");
        return k;
    }
    static String currency(JSONObject j)throws Exception{
        String c=j.optString("currency","YER");
        if(!c.equals("YER")&&!c.equals("SAR")&&!c.equals("USD"))throw new JSONException("Invalid currency");
        return c;
    }
    static long unit(JSONObject j)throws Exception{
        Object o=j.opt("unitMinor");
        if(o==null||o==JSONObject.NULL)return 0;
        if(!(o instanceof Number))throw new JSONException("Invalid unit value");
        long n=((Number)o).longValue();
        if(n<0||n>MAX_UNIT_MINOR||((Number)o).doubleValue()!=(double)n)throw new JSONException("Invalid unit value");
        return n;
    }
    static void checkCycle(JSONObject j)throws Exception{
        String k=kind(j);currency(j);long n=unit(j);
        if(k.equals("cash")&&n==0)throw new JSONException("Cash value required");
        if(k.equals("none")&&n!=0)throw new JSONException("Unvalued bag cannot have amount");
    }
    static void attach(JSONObject result,JSONObject bag,int q)throws Exception{
        checkCycle(bag);String kind=kind(bag);if(kind.equals("none")||unit(bag)==0)return;
        long value=Math.multiplyExact(unit(bag),q);
        if(value>Long.MAX_VALUE)throw new JSONException("Amount overflow");
        result.put("valueKind",kind).put("currency",currency(bag)).put("unitMinor",unit(bag)).put("amountMinor",value);
    }
    static void checkDelivery(JSONObject d)throws Exception{
        if(!d.has("amountMinor"))return; // Historic records carry no monetary value.
        if(!d.has("valueKind")||!d.has("currency")||!d.has("unitMinor"))throw new JSONException("Incomplete monetary snapshot");
        if(kind(d).equals("none"))throw new JSONException("Unexpected monetary snapshot");
        long u=unit(d),a=integer(d,"amountMinor",1,MAX_UNIT_MINOR*10000L);
        if(u<1||a<1)throw new JSONException("Invalid amount");
        currency(d);
    }
    private static long integer(JSONObject j,String key,long min,long max)throws Exception{
        Object o=j.get(key);
        if(!(o instanceof Number))throw new JSONException("Invalid amount");
        double x=((Number)o).doubleValue();long n=((Number)o).longValue();
        if(!Double.isFinite(x)||x!=n||n<min||n>max)throw new JSONException("Invalid amount");
        return n;
    }
}
