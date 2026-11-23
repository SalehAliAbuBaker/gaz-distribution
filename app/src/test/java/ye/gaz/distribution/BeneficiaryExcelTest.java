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
        JSONArray a=BeneficiaryExcel.read(populated(1000));assertEquals(1000,a.length());assertEquals("001",a.getJSONObject(0).getString("house"));assertEquals("5",a.getJSONObject(0).getString("members"));
        JSONObject data=new JSONObject().put("n",0).put("families",new JSONArray());assertEquals(1000,BeneficiaryExcel.append(data,a));assertEquals(1000,data.getLong("n"));assertThrows(Exception.class,()->BeneficiaryExcel.read(populated(1001)));assertThrows(Exception.class,()->BeneficiaryExcel.numericText("1e10000000"));
    }
    @Test public void thousandRowTemplateHasCorrectRange()throws Exception{
        String sheet=new String(BeneficiaryExcel.unzip(BeneficiaryExcel.template()).get("xl/worksheets/sheet1.xml"),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(sheet.contains("<dimension ref=\"A1:J1001\"/>"));
        assertTrue(sheet.contains("<row r=\"1001\">"));
        assertTrue(sheet.contains("<autoFilter ref=\"A1:J1001\"/>"));
    }
    @Test public void thousandRowsStillEnforceIdentityAndNameUniqueness()throws Exception{
        JSONObject data=new JSONObject().put("n",0).put("families",new JSONArray());
        JSONArray rows=new JSONArray();
        for(int i=0;i<1000;i++)rows.put(row(i+2,"مستفيد "+i).put("identityNo",String.valueOf(200000+i)));
        rows.getJSONObject(999).put("identityNo","٢٠٠٠٠٠");
        JSONObject preview=BeneficiaryExcel.preview(data,rows);
        assertEquals(999,preview.getJSONArray("valid").length());
        assertEquals(1,preview.getJSONArray("errors").length());
        assertThrows(Exception.class,()->BeneficiaryExcel.append(data,rows));
        assertEquals(0,data.getJSONArray("families").length());
        JSONArray overflow=new JSONArray();
        for(int i=0;i<1001;i++)overflow.put(row(i+2,"مستفيد "+i));
        assertThrows(Exception.class,()->BeneficiaryExcel.preview(data,overflow));
    }
    @Test public void familyLinkOptionalAndText()throws Exception{
        JSONObject data=new JSONObject().put("families",new JSONArray()).put("n",0);
        JSONArray rows=new JSONArray().put(row(2,"اسم أول").put("house","")).put(row(3,"اسم ثان").put("house","أسرة آل سالم"));
        assertEquals(2,BeneficiaryExcel.append(data,rows));assertEquals("",data.getJSONArray("families").getJSONObject(0).getString("house"));assertEquals("أسرة آل سالم",data.getJSONArray("families").getJSONObject(1).getString("house"));
    }
    @Test public void needAcceptsTextNumberAndBlank()throws Exception{
        JSONObject data=new JSONObject().put("families",new JSONArray()).put("n",0);
        JSONArray rows=new JSONArray().put(row(2,"اسم أول").put("need","شديدة")).put(row(3,"اسم ثان").put("need","٨٠٪")).put(row(4,"اسم ثالث").put("need",""));
        assertEquals(3,BeneficiaryExcel.append(data,rows));JSONArray f=data.getJSONArray("families");assertEquals("شديدة",f.getJSONObject(0).getString("need"));assertEquals("٨٠٪",f.getJSONObject(1).getString("need"));assertEquals("",f.getJSONObject(2).getString("need"));
    }
    @Test public void blankMembersAndTrackingNotesImport431WithoutFabricatingHouseholdSize()throws Exception{
        JSONObject data=new JSONObject().put("families",new JSONArray()).put("n",0);
        JSONArray records=new JSONArray();
        for(int i=1;i<=431;i++)records.put(row(i+1,"مستفيد تجريبي "+i).put("members","").put("notes",String.valueOf(i)).put("house",""));
        JSONObject preview=BeneficiaryExcel.preview(data,records);
        assertEquals(0,preview.getJSONArray("errors").length());
        assertEquals(431,preview.getJSONArray("valid").length());
        assertEquals(431,preview.getInt("tracking"));
        assertEquals(431,preview.getInt("trackingMatches"));
        assertEquals(1,preview.getLong("firstNo"));
        assertEquals(431,BeneficiaryExcel.append(data,records));
        assertEquals(431,data.getJSONArray("families").length());
        JSONObject first=data.getJSONArray("families").getJSONObject(0),last=data.getJSONArray("families").getJSONObject(430);
        assertTrue(first.isNull("members"));assertTrue(last.isNull("members"));
        assertEquals("1",first.getString("notes"));assertEquals("431",last.getString("notes"));
        assertEquals(1,first.getInt("no"));assertEquals(431,last.getInt("no"));
    }
    @Test public void trackingNotesDoNotOverrideExistingRegisterNumbers()throws Exception{
        JSONObject data=new JSONObject().put("families",new JSONArray()).put("n",20);
        JSONArray records=new JSONArray().put(row(2,"اسم مستقل").put("notes","1").put("members",""));
        JSONObject preview=BeneficiaryExcel.preview(data,records);
        assertEquals(1,preview.getInt("tracking"));assertEquals(0,preview.getInt("trackingMatches"));
        assertEquals(21,preview.getLong("firstNo"));
        BeneficiaryExcel.append(data,records);
        JSONObject imported=data.getJSONArray("families").getJSONObject(0);
        assertEquals(21,imported.getInt("no"));assertEquals("1",imported.getString("notes"));
    }
    @Test public void invalidKnownHouseholdSizeStillFails()throws Exception{
        JSONObject data=new JSONObject().put("families",new JSONArray()).put("n",0);
        for(String invalid:new String[]{"0","-1","1.5","غير محدد","1001"}){
            JSONArray records=new JSONArray().put(row(2,"اسم مستقل").put("members",invalid));
            assertEquals(1,BeneficiaryExcel.preview(data,records).getJSONArray("errors").length());
        }
    }
}
