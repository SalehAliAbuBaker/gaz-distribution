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
    static byte[] populated(int count)throws Exception{
        java.util.Map<String,byte[]> files=BeneficiaryExcel.unzip(BeneficiaryExcel.template());
        String sheet=new String(files.get("xl/worksheets/sheet1.xml"),java.nio.charset.StandardCharsets.UTF_8);
        int begin=sheet.indexOf("<row r=\"2\""),end=sheet.indexOf("</sheetData>",begin);StringBuilder rows=new StringBuilder();
        for(int i=2;i<count+2;i++)rows.append("<row r=\"").append(i).append("\"><c r=\"A").append(i).append("\" t=\"inlineStr\"><is><t>اسم ").append(i).append("</t></is></c><c r=\"B").append(i).append("\" t=\"inlineStr\"><is><t>001</t></is></c><c r=\"F").append(i).append("\"><v>5</v></c></row>");
        files.put("xl/worksheets/sheet1.xml",(sheet.substring(0,begin)+rows+sheet.substring(end)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();try(java.util.zip.ZipOutputStream out=new java.util.zip.ZipOutputStream(bytes)){for(java.util.Map.Entry<String,byte[]> e:files.entrySet()){out.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));out.write(e.getValue());out.closeEntry();}}return bytes.toByteArray();
    }
    @Test public void readFullBatchAndRejectOverflow()throws Exception{
        JSONArray a=BeneficiaryExcel.read(populated(100));assertEquals(100,a.length());assertEquals("001",a.getJSONObject(0).getString("house"));assertEquals("5",a.getJSONObject(0).getString("members"));
        JSONObject data=new JSONObject().put("n",0).put("families",new JSONArray());assertEquals(100,BeneficiaryExcel.append(data,a));assertEquals(100,data.getLong("n"));assertThrows(Exception.class,()->BeneficiaryExcel.read(populated(101)));assertThrows(Exception.class,()->BeneficiaryExcel.numericText("1e10000000"));
    }
}
