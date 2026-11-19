package ye.gaz.distribution;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.json.*;

/** Single-sheet XLSX template reader/writer, bounded and without formula execution. */
final class BeneficiaryExcel {
    static final String MIME="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String[] HEAD={"الاسم الكامل","رقم المنزل","مربع","المنطقة / الحي","رقم التواصل","عدد أفراد الأسرة","نسبة الاحتياج","التصنيفات","ملاحظات"};
    static final String[] KEYS={"name","house","square","area","phone","members","need","categories","notes"};
    static final int LIMIT=100,MAX=8*1024*1024;
    static final String NS="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    static final String REL="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static final String PKG="http://schemas.openxmlformats.org/package/2006/relationships";
    static String xml(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    static void add(ZipOutputStream z,String name,String value)throws Exception{z.putNextEntry(new ZipEntry(name));z.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"+value).getBytes(StandardCharsets.UTF_8));z.closeEntry();}
    static byte[] template()throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream z=new ZipOutputStream(bytes)){
            add(z,"[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>");
            add(z,"_rels/.rels","<Relationships xmlns=\""+PKG+"\"><Relationship Id=\"rId1\" Type=\""+REL+"/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            add(z,"xl/workbook.xml","<workbook xmlns=\""+NS+"\" xmlns:r=\""+REL+"\"><bookViews><workbookView/></bookViews><sheets><sheet name=\"المستفيدون\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            add(z,"xl/_rels/workbook.xml.rels","<Relationships xmlns=\""+PKG+"\"><Relationship Id=\"rId1\" Type=\""+REL+"/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\""+REL+"/styles\" Target=\"styles.xml\"/></Relationships>");
            add(z,"xl/styles.xml","<styleSheet xmlns=\""+NS+"\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Arial\"/></font><font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Arial\"/></font></fonts><fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF084890\"/><bgColor indexed=\"64\"/></patternFill></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"3\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFill=\"1\" applyFont=\"1\"/><xf numFmtId=\"49\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
            StringBuilder s=new StringBuilder("<worksheet xmlns=\""+NS+"\"><dimension ref=\"A1:I101\"/><sheetViews><sheetView rightToLeft=\"1\" workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"21\"/><cols>");int[] w={36,15,15,24,22,20,20,24,36};
            for(int i=0;i<9;i++)s.append("<col min=\"").append(i+1).append("\" max=\"").append(i+1).append("\" width=\"").append(w[i]).append("\" customWidth=\"1\"/>");
            s.append("</cols><sheetData><row r=\"1\" ht=\"28\" customHeight=\"1\">");
            for(int i=0;i<9;i++)s.append("<c r=\"").append((char)('A'+i)).append("1\" s=\"1\" t=\"inlineStr\"><is><t>").append(xml(HEAD[i])).append("</t></is></c>");
            s.append("</row>");for(int row=2;row<=101;row++){s.append("<row r=\"").append(row).append("\">");for(int i=0;i<9;i++)s.append("<c r=\"").append((char)('A'+i)).append(row).append("\" s=\"").append(i==5||i==6?0:2).append("\"/>");s.append("</row>");}
            s.append("</sheetData><autoFilter ref=\"A1:I101\"/></worksheet>");add(z,"xl/worksheets/sheet1.xml",s.toString());
        }return bytes.toByteArray();
    }
    static void check(boolean ok,String msg)throws IOException{if(!ok)throw new IOException(msg);}
    static Document parse(byte[] raw)throws Exception{
        String value=new String(raw,StandardCharsets.UTF_8);check(!value.contains("\u0000")&&!value.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")&&!value.toUpperCase(Locale.ROOT).contains("<!ENTITY"),"ملف XML غير آمن");
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder b=f.newDocumentBuilder();b.setEntityResolver((a,c)->{throw new org.xml.sax.SAXException("External entity");});
        return b.parse(new ByteArrayInputStream(raw));
    }
    static Map<String,byte[]> unzip(byte[] bytes)throws Exception{
        check(bytes.length<=MAX,"حجم الملف أكبر من المسموح");Map<String,byte[]> files=new HashMap<>();int total=0,entries=0;
        try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry e;byte[] buffer=new byte[8192];while((e=z.getNextEntry())!=null){
            check(++entries<=160,"ملف Excel معقد جدًا");String path=e.getName();check(!path.contains("..")&&!path.startsWith("/")&&!files.containsKey(path),"مسار ملف غير صالح");
            ByteArrayOutputStream out=new ByteArrayOutputStream();int n;while((n=z.read(buffer))!=-1){total+=n;check(total<=MAX,"حجم الملف بعد فك الضغط كبير");out.write(buffer,0,n);}files.put(path,out.toByteArray());z.closeEntry();
        }}return files;
    }
    static NodeList nodes(Element e,String name){return e.getElementsByTagNameNS("*",name);}
    static String first(Element e,String name){NodeList n=nodes(e,name);return n.getLength()==0?"":n.item(0).getTextContent();}
    static String texts(Element e){NodeList n=nodes(e,"t");StringBuilder b=new StringBuilder();for(int i=0;i<n.getLength();i++)b.append(n.item(i).getTextContent());return b.toString();}
    static String digit(String s){return AllocationSelection.norm(s).replace('٫','.');}
    static java.math.BigDecimal boundedNumber(String s)throws Exception{check(s.length()<=80,"قيمة رقمية طويلة");java.math.BigDecimal n=new java.math.BigDecimal(s);check(Math.abs((long)n.scale())<=100&&n.precision()<=80,"قيمة رقمية خارج النطاق");return n;}
    static String numericText(String s)throws Exception{try{return boundedNumber(s).stripTrailingZeros().toPlainString();}catch(Exception e){throw new IOException("قيمة رقمية غير صالحة");}}
    static JSONArray read(byte[] raw)throws Exception{
        Map<String,byte[]> files=unzip(raw);check(files.containsKey("xl/workbook.xml")&&files.containsKey("xl/_rels/workbook.xml.rels"),"اختر ملف XLSX من النموذج");
        Document wb=parse(files.get("xl/workbook.xml"));NodeList sheets=nodes(wb.getDocumentElement(),"sheet");Element selected=null;for(int i=0;i<sheets.getLength();i++){Element e=(Element)sheets.item(i);if("المستفيدون".equals(e.getAttribute("name")))selected=e;}
        check(selected!=null,"لم توجد ورقة المستفيدون؛ استخدم النموذج");String rid=selected.getAttributeNS(REL,"id"),path=null;NodeList rels=nodes(parse(files.get("xl/_rels/workbook.xml.rels")).getDocumentElement(),"Relationship");
        for(int i=0;i<rels.getLength();i++){Element e=(Element)rels.item(i);if(rid.equals(e.getAttribute("Id"))&&e.getAttribute("Type").endsWith("/worksheet")&&!"External".equals(e.getAttribute("TargetMode"))){String t=e.getAttribute("Target");path=t.startsWith("/")?t.substring(1):"xl/"+t;}}
        check(path!=null&&path.matches("xl/worksheets/[A-Za-z0-9_.-]+\\.xml")&&files.containsKey(path),"ورقة غير صالحة");
        List<String> shared=new ArrayList<>();if(files.containsKey("xl/sharedStrings.xml")){NodeList ns=nodes(parse(files.get("xl/sharedStrings.xml")).getDocumentElement(),"si");check(ns.getLength()<=30000,"نصوص كثيرة");for(int i=0;i<ns.getLength();i++)shared.add(texts((Element)ns.item(i)));}
        NodeList rows=nodes(parse(files.get(path)).getDocumentElement(),"row");check(rows.getLength()<=10000,"صفوف كثيرة");JSONArray result=new JSONArray();boolean header=false;Set<Integer> ids=new HashSet<>();
        for(int i=0;i<rows.getLength();i++){Element r=(Element)rows.item(i);int rn=Integer.parseInt(r.getAttribute("r"));check(rn>=1&&ids.add(rn),"صف مكرر");String[] vals=new String[9];Arrays.fill(vals,"");NodeList cells=nodes(r,"c");Set<Integer> seen=new HashSet<>();
            for(int j=0;j<cells.getLength();j++){Element c=(Element)cells.item(j);String ref=c.getAttribute("r"),t=c.getAttribute("t");check(ref.matches("[A-Z]+[0-9]+"),"عنوان خلية غير صالح");String rawValue=first(c,"v");boolean formula=nodes(c,"f").getLength()>0;String v;
                check(!formula,"الصف "+rn+": لا تستخدم صيغ Excel");
                if("s".equals(t)&&!rawValue.isEmpty()){int n=Integer.parseInt(rawValue);check(n>=0&&n<shared.size(),"نص غير صالح");v=shared.get(n);}else if("inlineStr".equals(t))v=texts(c);else if(rawValue.isEmpty())v="";else if(t.isEmpty()||"n".equals(t))v=numericText(rawValue);else if("str".equals(t))v=rawValue;else throw new IOException("الصف "+rn+": نوع خلية غير مدعوم");
                v=v.trim();String col=ref.replaceAll("[0-9]+$","");if(col.length()!=1||col.charAt(0)<'A'||col.charAt(0)>'I'){check(v.isEmpty(),"الصف "+rn+": أعمدة زائدة");continue;}int idx=col.charAt(0)-'A';check(seen.add(idx),"خلية مكررة");vals[idx]=v;
            }
            if(rn==1){for(int k=0;k<9;k++)check(HEAD[k].equals(vals[k]),"عناوين الأعمدة غير مطابقة للنموذج");header=true;continue;}
            boolean empty=true;for(String v:vals)if(!v.isEmpty()){empty=false;break;}if(empty)continue;
            check(header,"لا يوجد رأس النموذج");check(result.length()<LIMIT,"الحد الأقصى 100 مستفيد في الدفعة؛ قسّم الملف");
            JSONObject item=new JSONObject().put("row",rn);for(int k=0;k<9;k++)item.put(KEYS[k],vals[k]);result.put(item);
        }check(header,"النموذج غير مطابق");check(result.length()>0,"لا توجد أسماء في الملف");return result;
    }
    static JSONObject preview(JSONObject data,JSONArray input)throws Exception{
        JSONArray valid=new JSONArray(),errors=new JSONArray();Set<String> names=new HashSet<>();JSONArray old=data.getJSONArray("families");for(int i=0;i<old.length();i++)names.add(AllocationSelection.norm(old.getJSONObject(i).getString("name")));
        check(input.length()<=LIMIT,"الحد 100 مستفيد");
        for(int i=0;i<input.length();i++){JSONObject x=input.getJSONObject(i);int row=x.getInt("row");try{
            String name=x.getString("name").trim(),house=x.getString("house").trim();check(!name.isEmpty()&&!house.isEmpty(),"الاسم ورقم المنزل مطلوبان");check(name.length()<=200&&house.length()<=80&&x.getString("square").length()<=80&&x.getString("area").length()<=100&&x.getString("phone").length()<=50&&x.getString("categories").length()<=300&&x.getString("notes").length()<=1000,"إحدى القيم طويلة جدًا");
            int members=boundedNumber(digit(x.getString("members"))).intValueExact();check(members>=1&&members<=1000,"عدد أفراد الأسرة غير صالح");
            String need=x.getString("need");if(!need.isEmpty()){java.math.BigDecimal n=boundedNumber(digit(need));check(n.signum()>=0&&n.compareTo(java.math.BigDecimal.valueOf(100))<=0,"نسبة الاحتياج من 0 إلى 100");need=n.stripTrailingZeros().toPlainString();}
            check(names.add(AllocationSelection.norm(name)),"اسم مكرر داخل الملف أو التطبيق");
            JSONObject f=new JSONObject();for(String k:KEYS)f.put(k,x.getString(k));f.put("name",name).put("house",house).put("members",members).put("need",need).put("row",row);valid.put(f);
        }catch(Exception e){String msg=e instanceof IOException?e.getMessage():"عدد الأفراد أو نسبة الاحتياج غير صالح";errors.put("الصف "+row+": "+msg);}}
        return new JSONObject().put("valid",valid).put("errors",errors);
    }
    static int append(JSONObject data,JSONArray input)throws Exception{
        JSONObject result=preview(data,input);JSONArray valid=result.getJSONArray("valid");check(result.getJSONArray("errors").length()==0&&valid.length()>0,"البيانات تغيرت أو توجد أخطاء؛ أعد الاستيراد");long n=data.getLong("n");JSONArray families=data.getJSONArray("families");
        for(int i=0;i<valid.length();i++){JSONObject f=valid.getJSONObject(i);f.remove("row");f.put("id",UUID.randomUUID().toString()).put("no",++n);families.put(f);}data.put("n",n);return valid.length();
    }
}
