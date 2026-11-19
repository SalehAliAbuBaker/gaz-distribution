package ye.gaz.distribution;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class BeneficiaryExcelTest {
    static JSONObject row(int n,String name)throws Exception{return new JSONObject().put("row",n).put("name",name).put("house","001").put("square","2").put("area","").put("phone","0777").put("members","٥").put("need","80").put("categories","").put("notes","");}
    @Test public void emptyTemplateRecognized()throws Exception{try{BeneficiaryExcel.read(BeneficiaryExcel.template());fail();}catch(java.io.IOException e){assertEquals("لا توجد أسماء في الملف",e.getMessage());}}
    @Test public void duplicateBlockedAndNumbersAutoAssigned()throws Exception{
        JSONObject data=new JSONObject().put("families",new JSONArray()).put("n",7);
        JSONArray rows=new JSONArray().put(row(2,"أحمد سالم")).put(row(3,"احمد سالم"));
        assertEquals(1,BeneficiaryExcel.preview(data,rows).getJSONArray("errors").length());
        assertThrows(Exception.class,()->BeneficiaryExcel.append(data,rows));assertEquals(0,data.getJSONArray("families").length());
        assertEquals(1,BeneficiaryExcel.append(data,new JSONArray().put(row(2,"أحمد سالم"))));JSONObject f=data.getJSONArray("families").getJSONObject(0);assertEquals(8,f.getLong("no"));assertEquals("001",f.getString("house"));assertEquals("0777",f.getString("phone"));assertEquals(5,f.getInt("members"));
    }
}
