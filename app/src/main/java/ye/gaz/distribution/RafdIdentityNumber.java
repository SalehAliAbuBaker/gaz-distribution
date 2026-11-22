package ye.gaz.distribution;
import org.json.*;
import java.util.*;
final class RafdIdentityNumber {
    private RafdIdentityNumber(){}
    static String normalize(Object raw){
        if(raw==null||raw==JSONObject.NULL)return "";
        String input=String.valueOf(raw).trim();
        StringBuilder b=new StringBuilder(input.length());
        for(int i=0;i<input.length();i++){
            char c=input.charAt(i);
            if(Character.isWhitespace(c)||c=='-'||c=='\u2010'||c=='\u2011'||c=='\u2012'||c=='\u2013'||c=='\u2014'||c=='\u2212')continue;
            if(c>='0'&&c<='9')b.append(c);
            else if(c>='\u0660'&&c<='\u0669')b.append((char)('0'+c-'\u0660'));
            else if(c>='\u06F0'&&c<='\u06F9')b.append((char)('0'+c-'\u06F0'));
            else throw new IllegalArgumentException("رقم الهوية يجب أن يكون أرقامًا فقط");
        }
        String id=b.toString();
        if(id.length()>30)throw new IllegalArgumentException("رقم الهوية طويل");
        return id;
    }
    static void validateUnique(JSONArray families)throws Exception{
        Set<String> seen=new HashSet<>();
        for(int i=0;i<families.length();i++){
            JSONObject f=families.getJSONObject(i);if(f.has("identityNo")&&!(f.opt("identityNo") instanceof String))throw new JSONException("Invalid identity number");
            String id=normalize(f.optString("identityNo",""));
            if(!id.isEmpty()&&!seen.add(id))throw new JSONException("Duplicate identity number");
        }
    }
}