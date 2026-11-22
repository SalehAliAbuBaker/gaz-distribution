package ye.gaz.distribution;
import com.google.zxing.Result;
import com.google.zxing.oned.Code39Reader;
import com.google.zxing.common.BitArray;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.Map;

/** Keep this mapping synchronized with gaz-registry-print.js's Code39 bars. */
public final class RafdCardBarcodeTest {
  private static final Map<Character,String> M=new HashMap<>();
  static {
    M.put('*',"100101101101");M.put('R',"110101011001");
    M.put('F',"101101100101");M.put('D',"101011001011");
    M.put('0',"101001101101");M.put('1',"110100101011");
    M.put('2',"101100101011");M.put('3',"110110010101");
    M.put('4',"101001101011");M.put('5',"110100110101");
    M.put('6',"101100110101");M.put('7',"101001011011");
    M.put('8',"110100101101");M.put('9',"101100101101");
  }
  static BitArray bitmap(String input){
    StringBuilder bars=new StringBuilder("000000000000000000000000");
    for(char c:("*"+input+"*").toCharArray()){String code=M.get(c);if(code==null)throw new IllegalArgumentException();bars.append(code).append('0');}
    bars.append("000000000000000000000000");
    BitArray result=new BitArray(bars.length());
    for(int i=0;i<bars.length();i++)if(bars.charAt(i)=='1')result.set(i);
    return result;
  }
  @Test public void scanPrintedCodes()throws Exception{
    for(String code:new String[]{"RFD000001","RFD001234","RFD123456789","RFD000000"}){
      Result result=new Code39Reader().decodeRow(0,bitmap(code),null);
      assertEquals(code,result.getText());
    }
  }
}