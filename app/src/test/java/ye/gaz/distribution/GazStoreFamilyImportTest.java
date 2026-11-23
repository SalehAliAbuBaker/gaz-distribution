package ye.gaz.distribution;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Map;

/** Regression: Excel preview may accept an unknown size, and SQLite validation must accept it too. */
public class GazStoreFamilyImportTest {
    static JSONObject emptyStore() throws Exception {
        return new JSONObject()
            .put("area","منطقة اختبار")
            .put("n",0)
            .put("families",new JSONArray())
            .put("cycles",new JSONArray())
            .put("deliveries",new JSONArray())
            .put("users",new JSONArray());
    }
    @Test public void unknownHouseholdSizePersistsThroughFullStoreValidation() throws Exception {
        JSONObject state=emptyStore(), row=BeneficiaryExcelTest.row(2,"مستفيد 1")
            .put("members","").put("notes","1");
        JSONArray input=new JSONArray().put(row);
        assertEquals(0,BeneficiaryExcel.preview(state,input).getJSONArray("errors").length());
        assertEquals(1,BeneficiaryExcel.append(state,input));
        JSONObject saved=state.getJSONArray("families").getJSONObject(0);
        assertTrue(saved.isNull("members"));
        assertEquals("1",saved.getString("notes"));
        assertEquals(1,saved.getInt("no"));
        Map<String,String> encoded=GazStore.flatten(state);
        assertEquals(2,encoded.size());
        assertTrue(encoded.values().toString().contains("\\"members\\":null"));
    }
    @Test public void imported431UnknownHouseholdsPassFullStoreValidation() throws Exception {
        JSONObject state=emptyStore();
        JSONArray input=new JSONArray();
        for(int i=1;i<=431;i++)input.put(BeneficiaryExcelTest.row(i+1,"مستفيد "+i)
            .put("members","").put("notes",String.valueOf(i)));
        JSONObject preview=BeneficiaryExcel.preview(state,input);
        assertEquals(431,preview.getJSONArray("valid").length());
        assertEquals(0,preview.getJSONArray("errors").length());
        assertEquals(431,preview.getInt("trackingMatches"));
        assertEquals(431,BeneficiaryExcel.append(state,input));
        assertEquals(431,state.getJSONArray("families").length());
        assertEquals(432,GazStore.flatten(state).size());
        assertEquals(431,state.getLong("n"));
        JSONObject last=state.getJSONArray("families").getJSONObject(430);
        assertEquals(431,last.getInt("no"));
        assertEquals("431",last.getString("notes"));
        assertTrue(last.isNull("members"));
    }
    @Test public void zeroNegativeAndInvalidSizesRemainRejectedAtStoreBoundary() throws Exception {
        JSONObject state=emptyStore(),family=new JSONObject()
            .put("id","family-1").put("no",1).put("name","مستفيد")
            .put("house","").put("members",JSONObject.NULL);
        state.put("n",1).getJSONArray("families").put(family);
        assertTrue(GazStore.validFamilyMembers(family));
        GazStore.flatten(state);
        for(Object invalid:new Object[]{0,-1,1001,1.5,"", "غير محدد"}){
            family.put("members",invalid);
            assertFalse(GazStore.validFamilyMembers(family));
            assertThrows(Exception.class,()->GazStore.flatten(state));
        }
        family.remove("members");
        assertFalse(GazStore.validFamilyMembers(family));
        assertThrows(Exception.class,()->GazStore.flatten(state));
        family.put("members",5);
        assertTrue(GazStore.validFamilyMembers(family));
        GazStore.flatten(state);
    }
}
