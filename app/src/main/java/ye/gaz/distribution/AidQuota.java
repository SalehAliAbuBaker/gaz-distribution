package ye.gaz.distribution;

import org.json.JSONObject;

/** Optional strict greater-than tiers; old cycles keep their original quota. */
final class AidQuota {
    private AidQuota(){}
    static int threshold(JSONObject c,String field){
        Object raw=c.opt(field);
        if(raw==null||raw==JSONObject.NULL)return 0;
        String s=String.valueOf(raw).trim();
        if(s.isEmpty())return 0;
        if(!s.matches("[0-9]+"))throw new IllegalArgumentException("Invalid family threshold");
        long n=Long.parseLong(s);
        if(n<1||n>1000000)throw new IllegalArgumentException("Invalid family threshold");
        return (int)n;
    }
    static void validate(JSONObject c){
        int two=threshold(c,"aidTwoAbove"),three=threshold(c,"aidThreeAbove");
        if(two>0&&three>0&&three<=two)throw new IllegalArgumentException("Invalid family tiers");
        if((two>0||three>0)&&c.optInt("quota",0)!=1)throw new IllegalArgumentException("Tiered quota requires base one");
    }
    static int forFamily(JSONObject c,JSONObject f){
        validate(c);
        int n=f.optInt("members",0),two=threshold(c,"aidTwoAbove"),three=threshold(c,"aidThreeAbove");
        if(three>0&&n>three)return 3;
        if(two>0&&n>two)return 2;
        return c.optInt("quota",0);
    }
}
