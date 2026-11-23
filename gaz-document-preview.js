
(()=>{'use strict';
const id=x=>document.getElementById(x);
const LIB='./vendor/ratib-pdfmake-rtl-0.2.23.js';
let enginePromise,fontsPromise,opened=false,token=0,prepared=null,snapshot=null,scale=1,pinch=null,historyOwned=false;

const OPTION_GROUPS={meta:'بيانات الحقيبة',columns:'أعمدة المستفيدين والتوزيع',extra:'أجزاء الكشف'};
const REPORT_FIELDS=[
 {key:'docNo',label:'رقم المستند',group:'meta',on:true},
 {key:'cycleDate',label:'تاريخ الحقيبة',group:'meta',on:true},
 {key:'bagName',label:'اسم الحقيبة',group:'meta',on:true},
 {key:'donor',label:'المسؤول / المورد',group:'meta',on:true},
 {key:'details',label:'التفاصيل',group:'meta',on:true},
 {key:'valueKind',label:'نوع القيمة',group:'meta',on:true},
 {key:'unitValue',label:'قيمة العطية',group:'meta',on:true},
 {key:'stockTotal',label:'إجمالي عطايا الحقيبة',group:'meta',on:true},
 {key:'familyCount',label:'عدد الأسر',group:'meta',on:true},
 {key:'issued',label:'تاريخ الإصدار',group:'meta',on:true},
 {key:'number',label:'الترقيم',group:'columns',on:true},
 {key:'registerNo',label:'رقم المسجل',group:'columns',on:false},
 {key:'name',label:'اسم المستفيد',group:'columns',on:true,required:true},
 {key:'house',label:'رابط الأسرة',group:'columns',on:true},
 {key:'square',label:'المربع',group:'columns',on:true},
 {key:'area',label:'المنطقة',group:'columns',on:true},
 {key:'members',label:'عدد الأفراد',group:'columns',on:true},
 {key:'category',label:'الفئة',group:'columns',on:false},
 {key:'quantity',label:'الكمية المصروفة',group:'columns',on:true,required:true},
 {key:'value',label:'القيمة النقدية / المقابلة',group:'columns',on:false},
 {key:'phone',label:'رقم الهاتف',group:'columns',on:false},
 {key:'identityNo',label:'رقم الهوية',group:'columns',on:false},
 {key:'basmala',label:'البسملة',group:'extra',on:true},
 {key:'entity',label:'هوية المؤسسة وترويسها',group:'extra',on:true},
 {key:'totals',label:'إجمالي العطايا',group:'extra',on:true},
 {key:'signatures',label:'خانات التوقيع والاعتماد',group:'extra',on:true},
 {key:'footer',label:'ترقيم الصفحات',group:'extra',on:true}
];
const PREFERENCES_KEY='rafd-statement-visible-fields-v1';
const defaultOptions=()=>Object.fromEntries(REPORT_FIELDS.map(f=>[f.key,f.on]));
function loadOptions(s){
 const result=defaultOptions();let stored=null;
 try{stored=JSON.parse(localStorage.getItem(PREFERENCES_KEY)||'null')}catch{}
 if(stored&&typeof stored==='object'&&!Array.isArray(stored)){
  for(const field of REPORT_FIELDS)if(!field.required&&typeof stored[field.key]==='boolean')result[field.key]=stored[field.key];
 }else if(s){
  result.value=s.cycle.valueKind!=='none'||s.rows.some(r=>r.amountMinor!=null);
  result.signatures=s.identity.signatures;
 }
 return result;
}
function saveOptions(options){try{localStorage.setItem(PREFERENCES_KEY,JSON.stringify(options))}catch{}}
const htmlSafe=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;','\'':'&#39;'}[c]));
let options=defaultOptions();

const plain=v=>String(v??'').replace(/[\u061C\u200E\u200F\u202A-\u202E\u2066-\u2069]/g,'').trim();
function capture(d,area,issued,identity={}){return {identity:{entity:plain(identity.entity),letterhead:plain(identity.letterhead),address:plain(identity.address),signatures:identity.signatures!==false,preparedRole:plain(identity.preparedRole)||'إعداد المختص',preparedName:plain(identity.preparedName),approvedRole:plain(identity.approvedRole)||'اعتماد المسؤول',approvedName:plain(identity.approvedName)},cycle:{title:plain(d.c.title),docNo:plain(d.c.docNo)||'-',date:plain(d.c.date),donor:plain(d.c.donor)||'-',details:plain(d.c.details)||'-',valueKind:plain(d.c.valueKind)||'none',unitMinor:Number(d.c.unitMinor)||0,currency:plain(d.c.currency)||'YER',stockTotal:d.c.stockTotal==null?null:Number(d.c.stockTotal)},area:plain(area),issued,mode:d.mode,rows:d.rows.map(r=>({name:plain(r.f.name),registerNo:plain(r.f.no),identityNo:plain(r.f.identityNo),phone:plain(r.f.phone),house:plain(r.f.house)||'—',square:plain(r.f.square)||'—',area:plain(r.f.area||area)||'—',members:r.f.members==null||String(r.f.members).trim()===''?'غير محدد':plain(r.f.members),category:plain(r.f.categories)||'-',quantity:Number(r.d?.q)||0,amountMinor:Number.isSafeInteger(r.d?.amountMinor)?r.d.amountMinor:null,valueKind:plain(r.d?.valueKind),currency:plain(r.d?.currency)}))}}
const moneyName=c=>({YER:'ريال يمني',SAR:'ريال سعودي',USD:'دولار أمريكي'})[c]||'';
function moneyFormat(minor,currency){
 if(!Number.isSafeInteger(minor)||minor<0)return '—';
 return new Intl.NumberFormat('en-US',{minimumFractionDigits:minor%100?2:0,maximumFractionDigits:2}).format(minor/100)+' '+moneyName(currency);
}
function totals(s){
 const map=new Map();
 for(const r of s.rows){if(!Number.isSafeInteger(r.amountMinor)||r.amountMinor<=0)continue;
  const key=(r.valueKind||'estimated')+'|'+r.currency;map.set(key,(map.get(key)||0)+r.amountMinor)}
 return [...map].map(([k,v])=>{const [kind,currency]=k.split('|');return [(kind==='cash'?'إجمالي النقد المصروف':'إجمالي القيمة التقديرية'),moneyFormat(v,currency)]});
}
const titleOf=s=>s.mode==='pending'?'كشف الأسر التي لم تستلم':'كشف توزيع المساعدات';
const totalOf=s=>s.rows.reduce((sum,r)=>sum+r.quantity,0);

function metaItems(s,o){
 const c=s.cycle;const fields={
  docNo:['رقم المستند',c.docNo],cycleDate:['تاريخ الحقيبة',c.date],
  bagName:['اسم الحقيبة',c.title],donor:['المسؤول / المورد',c.donor],
  details:['التفاصيل',c.details],valueKind:['نوع القيمة',c.valueKind==='cash'?'نقدية':c.valueKind==='estimated'?'عينية بقيمة مقابلة':'عينية بلا قيمة'],
  unitValue:['قيمة العطية',c.unitMinor?moneyFormat(c.unitMinor,c.currency):'—'],
  stockTotal:['إجمالي عطايا الحقيبة',c.stockTotal??'غير محدد'],
  familyCount:['عدد الأسر',String(s.rows.length)],issued:['تاريخ الإصدار',s.issued]
 };
 return REPORT_FIELDS.filter(f=>f.group==='meta'&&o[f.key]).map(f=>fields[f.key]).filter(Boolean);
}
function visibleColumns(s,o){
 const columns={
  number:{title:'م',weight:24,align:'center',get:(r,i)=>String(i+1)},
  registerNo:{title:'رقم المسجل',weight:52,align:'center',get:r=>r.registerNo||'—'},
  name:{title:'المسجل',weight:160,get:r=>r.name},
  house:{title:'رابط الأسرة',weight:79,get:r=>r.house},
  square:{title:'المربع',weight:68,get:r=>r.square},
  area:{title:'المنطقة',weight:107,get:r=>r.area},
  members:{title:'الأفراد',weight:46,align:'center',get:r=>r.members},
  category:{title:'الفئة',weight:59,get:r=>r.category||'—'},
  quantity:{title:'الكمية',weight:48,align:'center',get:r=>r.quantity?String(r.quantity):'—'},
  value:{title:'القيمة النقدية / المقابلة',weight:110,get:r=>r.amountMinor!=null?moneyFormat(r.amountMinor,r.currency):'—'},
  phone:{title:'الهاتف',weight:79,align:'center',get:r=>r.phone||'—'},
  identityNo:{title:'الهوية',weight:80,align:'center',get:r=>r.identityNo||'—'}
 };
 return REPORT_FIELDS.filter(f=>f.group==='columns'&&o[f.key]).map(f=>({key:f.key,...columns[f.key]}));
}
function columnWidths(cols){
 // A4 portrait inner width is about 545 pt. Scale, never crop an optional field.
 const sum=cols.reduce((n,c)=>n+c.weight,0);
 const available=546-cols.length*6;return cols.map(c=>Math.round(c.weight*Math.min(1,available/sum)*10)/10);
}

const filename=s=>('رفد_كشف_توزيع_المساعدات_'+s.cycle.docNo+'_'+s.cycle.title).replace(/[\\/:*?"<>|\s]+/g,'_').slice(0,160)+'.pdf';

function definition(s,o=options){
 const cell=(v,extra={})=>({text:String(v??''),alignment:'right',...extra});
 const grid={hLineWidth:()=>.45,vLineWidth:()=>.45,hLineColor:()=> '#94a3b8',vLineColor:()=> '#94a3b8',paddingLeft:()=>3,paddingRight:()=>3,paddingTop:()=>4,paddingBottom:()=>4};
 const entries=metaItems(s,o),meta=[];
 for(let i=0;i<entries.length;i+=2){
  const first=entries[i],second=entries[i+1]||['',''];
  meta.push([cell(second[1]),cell(second[0],{bold:true,fillColor:'#f1f5f9'}),cell(first[1]),cell(first[0],{bold:true,fillColor:'#f1f5f9'})]);
 }
 // PDF table visual order is left-to-right; source order is RTL.
 const displayCols=visibleColumns(s,o),cols=displayCols.slice().reverse(),totalKey=displayCols[0].key;
 const body=[
  cols.map(col=>cell(col.title,{bold:true,alignment:'center',fillColor:'#e2e8f0'})),
  ...s.rows.map((r,i)=>cols.map(col=>cell(col.get(r,i),{alignment:col.align||'right',bold:col.key==='quantity'})))
 ];
 if(!s.rows.length)body.push([cell('لا توجد أسر مطابقة',{colSpan:cols.length,alignment:'center'}),...cols.slice(1).map(()=>({}))]);
 if(o.totals){
  body.push(cols.map(col=>cell(col.key===totalKey?'إجمالي العطايا':col.key==='quantity'?String(totalOf(s)):'',{bold:true,alignment:col.align||'right',fillColor:'#f1f5f9'})));
  if(o.value)for(const [label,amount] of totals(s)){
   body.push(cols.map(col=>cell(col.key===totalKey?label:col.key==='value'?amount:'',{bold:true,fillColor:'#e5f4ed'})));
  }
 }
 const header=[];
 if(o.basmala)header.push({text:'بسم الله الرحمن الرحيم',alignment:'center',bold:true,fontSize:11,margin:[0,0,0,4]});
 if(o.entity&&(s.identity.entity||s.identity.letterhead||s.identity.address))header.push({columns:[{width:'*',text:s.identity.address,alignment:'left',fontSize:9},{width:'*',stack:[{text:s.identity.entity,bold:true,fontSize:11},{text:s.identity.letterhead,fontSize:9}],alignment:'right'}],columnGap:10,margin:[0,1,0,6]});
 const compact=cols.length>8;
 return {pageSize:'A4',pageOrientation:'portrait',pageMargins:[24,24,24,34],defaultStyle:{font:'Tajawal',fontSize:compact?7:8.5,color:'#111827',alignment:'right',lineHeight:1.13},info:{title:titleOf(s),creator:'رفد'},content:[
 ...header,
 {text:titleOf(s),bold:true,alignment:'center',fontSize:16,margin:[0,2,0,9]},
 ...(meta.length?[{table:{widths:['*',65,'*',65],body:meta,dontBreakRows:true},layout:grid,margin:[0,0,0,9]}]:[]),
 {table:{headerRows:1,widths:columnWidths(cols),body,dontBreakRows:true,keepWithHeaderRows:1},layout:grid,margin:[0,0,0,15]},
 ...(o.signatures?[{unbreakable:true,table:{widths:['*','*'],body:[['approved','prepared'].map(k=>({stack:[{text:s.identity[k+'Role'],bold:true},{text:'الاسم: '+(s.identity[k+'Name']||'.....................................'),margin:[0,6,0,0]},{text:'التوقيع: ..................................',margin:[0,6,0,0]}],margin:6}))]},layout:grid}]:[])
 ],footer:o.footer?(page,count)=>({text:'صفحة '+page+' من '+count,alignment:'center',fontSize:8,color:'#64748b',margin:[0,7,0,0]}):undefined};
}

function engine(){if(window.pdfMake?.createPdf)return Promise.resolve(window.pdfMake);if(!enginePromise)enginePromise=new Promise((resolve,reject)=>{let script=document.createElement('script');script.src=LIB;script.onload=()=>window.pdfMake?.createPdf?resolve(window.pdfMake):reject(Error('محرك PDF غير صالح'));script.onerror=()=>{script.remove();reject(Error('محرك PDF غير متاح، تأكد من اكتمال تحديث التطبيق'))};document.head.append(script)}).catch(e=>{enginePromise=null;throw e});return enginePromise}
function b64(bytes){let binary='';for(let i=0;i<bytes.length;i+=16384)binary+=String.fromCharCode(...bytes.subarray(i,i+16384));return btoa(binary)}
function loadFonts(){if(!fontsPromise)fontsPromise=Promise.all(['tajawal_regular.ttf','tajawal_bold.ttf'].map(async f=>{let r=await fetch('./'+f,{cache:'force-cache'});if(!r.ok)throw Error('تعذر تحميل خط تجوّل');let b=new Uint8Array(await r.arrayBuffer());if(b.length<10000||b[0]!==0||b[1]!==1)throw Error('خط PDF غير صالح');return [f,b64(b)]})).then(Object.fromEntries).catch(e=>{fontsPromise=null;throw e});return fontsPromise}
async function makePDF(s,o=options){let [pdf,vfs]=await Promise.all([engine(),loadFonts()]);pdf.vfs={...(pdf.vfs||{}),...vfs};pdf.fonts={...(pdf.fonts||{}),Tajawal:{normal:'tajawal_regular.ttf',bold:'tajawal_bold.ttf',italics:'tajawal_regular.ttf',bolditalics:'tajawal_bold.ttf'}};let blob=await new Promise((resolve,reject)=>{try{pdf.createPdf(definition(s,o)).getBlob(b=>b?.size>100?resolve(b):reject(Error('تعذر إنشاء PDF')))}catch(e){reject(e)}});if((await blob.slice(0,5).text())!=='%PDF-')throw Error('الملف الناتج ليس PDF صالحًا');return new File([blob],filename(s),{type:'application/pdf'})}
async function createCustomFile(doc,name){
 let [pdf,vfs]=await Promise.all([engine(),loadFonts()]);
 pdf.vfs={...(pdf.vfs||{}),...vfs};
 pdf.fonts={...(pdf.fonts||{}),Tajawal:{normal:'tajawal_regular.ttf',bold:'tajawal_bold.ttf',italics:'tajawal_regular.ttf',bolditalics:'tajawal_bold.ttf'}};
 let blob=await new Promise((resolve,reject)=>{try{pdf.createPdf(doc).getBlob(b=>b?.size>100?resolve(b):reject(Error('تعذر إنشاء PDF')))}catch(e){reject(e)}});
 if((await blob.slice(0,5).text())!=='%PDF-')throw Error('ملف PDF غير صالح');
 return new File([blob],name,{type:'application/pdf'});
}
window.GazDocumentPdf={capture,definition,makePDF,filename,createCustomFile};
const host=document.createElement('div');host.id='gazPreview';host.hidden=true;host.setAttribute('role','dialog');host.setAttribute('aria-modal','true');host.setAttribute('aria-labelledby','gazPreviewTitle');
host.innerHTML='<div class="gaz-preview-head"><h2 id="gazPreviewTitle">معاينة الكشف</h2><button id="gazPreviewClose" class="muted" type="button">إغلاق ×</button></div><div class="gaz-preview-toolbar"><button id="gazPreviewPrint" type="button">طباعة</button><button id="gazPreviewShare" type="button" disabled>مشاركة</button><button id="gazPreviewOptions" type="button" aria-expanded="false" aria-controls="gazPreviewOptionsPanel">تخصيص الكشف</button><button id="gazPreviewMore" type="button" aria-expanded="false" aria-controls="gazPreviewExtras">المزيد</button><div id="gazPreviewExtras" hidden><button id="gazPreviewExport" type="button" disabled>تصدير PDF</button></div></div><div class="gaz-zoom"><button id="gazZoomMinus" type="button" aria-label="تصغير">−</button><output id="gazZoomValue">100%</output><button id="gazZoomPlus" type="button" aria-label="تكبير">+</button><button id="gazZoomFit" type="button">ملاءمة الشاشة</button></div><div id="gazPreviewStatus" role="status"></div><div id="gazPreviewViewport" tabindex="0"><div id="gazPreviewExtent"><article id="gazPreviewPaper"></article></div></div>';
document.body.append(host);
const more=id('gazPreviewMore'),extra=id('gazPreviewExtras');function closePreviewMore(){extra.hidden=true;more.setAttribute('aria-expanded','false')}more.onclick=()=>{extra.hidden=!extra.hidden;more.setAttribute('aria-expanded',String(!extra.hidden))};host.addEventListener('click',e=>{if(!more.contains(e.target)&&!extra.contains(e.target))closePreviewMore()});

const optionButton=id('gazPreviewOptions'),optionsPanel=document.createElement('section');
optionsPanel.id='gazPreviewOptionsPanel';optionsPanel.hidden=true;optionsPanel.setAttribute('aria-label','خيارات إظهار بيانات الكشف');
host.querySelector('.gaz-zoom').before(optionsPanel);
function paintOptions(){
 optionsPanel.innerHTML='<p class="gaz-options-note">اختر البيانات التي تريد ظهورها في المعاينة والطباعة وملف PDF. اسم المستفيد والكمية ثابتان لضمان سلامة الكشف.</p>'+
 Object.entries(OPTION_GROUPS).map(([group,title])=>'<fieldset class="gaz-options-group"><legend>'+title+'</legend><div class="gaz-options-grid">'+
 REPORT_FIELDS.filter(f=>f.group===group).map(f=>'<label><input type="checkbox" data-report-field="'+f.key+'" '+(options[f.key]?'checked ':'')+(f.required?'disabled ':'')+'>'+f.label+'</label>').join('')+
 '</div></fieldset>').join('');
}
function renderReportPaper(s,o){
 const cols=visibleColumns(s,o),meta=metaItems(s,o);
 paper.style.setProperty('--gaz-table-scale',cols.length>8?'.78':'1');
 const widthSum=cols.reduce((n,c)=>n+c.weight,0);
 const colgroup='<colgroup>'+cols.map(c=>'<col style="width:'+(100*c.weight/widthSum).toFixed(2)+'%">').join('')+'</colgroup>';
 const esc=htmlSafe;
 const tableRows=s.rows.map((r,i)=>'<tr>'+cols.map(c=>'<td'+(c.align==='center'?' class="gaz-center"':'')+'>'+esc(c.get(r,i))+'</td>').join('')+'</tr>').join('');
 const sum=o.totals?'<tr class="gaz-report-total">'+cols.map(c=>'<td>'+esc(c.key===cols[0].key?'إجمالي العطايا':c.key==='quantity'?String(totalOf(s)):'')+'</td>').join('')+'</tr>':'';
 const moneyRows=o.totals&&o.value?totals(s).map(([name,amount])=>'<tr class="gaz-report-total">'+cols.map(c=>'<td>'+esc(c.key===cols[0].key?name:c.key==='value'?amount:'')+'</td>').join('')+'</tr>').join(''):'';
 const metaRows=[];for(let i=0;i<meta.length;i+=2){const a=meta[i],b=meta[i+1];metaRows.push('<tr><th>'+esc(a[0])+'</th><td>'+esc(a[1])+'</td>'+(b?'<th>'+esc(b[0])+'</th><td>'+esc(b[1])+'</td>':'<th></th><td></td>')+'</tr>')}
 const sign=k=>'<div><strong>'+esc(s.identity[k+'Role'])+'</strong><p>الاسم: '+esc(s.identity[k+'Name']||'................................')+'</p><p>التوقيع: ................................</p></div>';
 const entity=s.identity;
 paper.innerHTML='<div class="gaz-report-header">'+(o.basmala?'<div class="gaz-report-basmala">بسم الله الرحمن الرحيم</div>':'')+
 (o.entity?'<div class="gaz-report-identity"><div><strong>'+esc(entity.entity)+'</strong><small>'+esc(entity.letterhead)+'</small></div><small>'+esc(entity.address)+'</small></div>':'')+
 '<h1>'+esc(titleOf(s))+'</h1></div>'+
 (metaRows.length?'<table class="gaz-report-meta"><tbody>'+metaRows.join('')+'</tbody></table>':'')+
 '<table class="gaz-report-table">'+colgroup+'<thead><tr>'+cols.map(c=>'<th>'+esc(c.title)+'</th>').join('')+'</tr></thead><tbody>'+(tableRows||'<tr><td colspan="'+cols.length+'">لا توجد أسر مطابقة</td></tr>')+sum+moneyRows+'</tbody></table>'+
 (o.signatures?'<div class="gaz-report-signatures">'+sign('prepared')+sign('approved')+'</div>':'')+
 (o.footer?'<p class="gaz-report-page">ترقيم الصفحات يظهر في ملف PDF والطباعة</p>':'');
}
function refreshReport(){
 if(!opened||!snapshot)return;
 renderReportPaper(snapshot,options);paintOptions();zoom(scale);
 prepared=null;controls(false);status.textContent='جارٍ تجهيز PDF بالإعدادات المختارة…';
 const mine=++token,s=snapshot,settings={...options};
 makePDF(s,settings).then(file=>{if(!opened||mine!==token||!permitted())return;prepared=file;controls(true);status.textContent='جاهز للطباعة والمشاركة وتصدير PDF';}).catch(e=>{if(opened&&mine===token){status.textContent='تعذر تجهيز PDF. راجع اختيارات الحقول أو أعد فتح المعاينة.';console.error(e)}});
}
optionButton.onclick=()=>{
 const show=optionsPanel.hidden;optionsPanel.hidden=!show;optionButton.setAttribute('aria-expanded',String(show));
 if(show){closePreviewMore();paintOptions()}
};
optionsPanel.addEventListener('change',e=>{
 const key=e.target?.dataset?.reportField;
 const field=REPORT_FIELDS.find(f=>f.key===key);
 if(!field||field.required||!snapshot)return;
 options[key]=!!e.target.checked;saveOptions(options);refreshReport();
});

const viewport=id('gazPreviewViewport'),paper=id('gazPreviewPaper'),extent=id('gazPreviewExtent'),status=id('gazPreviewStatus');
function zoom(v){scale=Math.max(.25,Math.min(4,v));paper.style.transform='scale('+scale+')';extent.style.width=Math.ceil(794*scale)+'px';extent.style.height=Math.ceil(Math.max(paper.scrollHeight,1123)*scale)+'px';id('gazZoomValue').textContent=Math.round(scale*100)+'%';id('gazZoomMinus').disabled=scale<=.25;id('gazZoomPlus').disabled=scale>=4}
function fit(){zoom(Math.min(1,Math.max(1,viewport.clientWidth-20)/794));viewport.scrollTop=0;viewport.scrollLeft=0}
id('gazZoomMinus').onclick=()=>zoom(scale-.25);id('gazZoomPlus').onclick=()=>zoom(scale+.25);id('gazZoomFit').onclick=fit;
const distance=t=>Math.hypot(t[0].clientX-t[1].clientX,t[0].clientY-t[1].clientY);
viewport.addEventListener('touchstart',e=>{if(e.touches.length===2){pinch={d:distance(e.touches),s:scale};if(e.cancelable)e.preventDefault()}},{passive:false});
viewport.addEventListener('touchmove',e=>{if(pinch&&e.touches.length===2){if(e.cancelable)e.preventDefault();if(pinch.d)zoom(pinch.s*distance(e.touches)/pinch.d)}},{passive:false});
['touchend','touchcancel'].forEach(x=>viewport.addEventListener(x,()=>pinch=null,{passive:true}));
window.addEventListener('resize',()=>{if(opened)zoom(scale)});
function permitted(){return typeof can==='function'&&can('view')&&can('reports')}
function controls(ready){id('gazPreviewShare').disabled=!ready;id('gazPreviewExport').disabled=!ready}
function hide(){if(!opened)return;closePreviewMore();optionsPanel.hidden=true;optionButton.setAttribute('aria-expanded','false');opened=false;token++;prepared=null;snapshot=null;host.hidden=true;paper.replaceChildren();document.body.classList.remove('gaz-preview-open');document.querySelector('main').inert=false;document.querySelector('body>header').inert=false;document.getElementById('rafdNav').inert=false;pinch=null;historyOwned=false}
function close(){let owned=historyOwned;hide();if(owned&&history.state?.gazDocumentPreview)history.back()}
id('gazPreviewClose').onclick=close;window.addEventListener('popstate',()=>{if(opened)hide()});document.addEventListener('keydown',e=>{if(opened&&e.key==='Escape'){e.preventDefault();close()}});
function openPreview(intent='preview'){
 if(opened)return;if(!permitted())return alert('ليس لديك صلاحية عرض الكشوف');const d=statement();if(!d)return alert('اختر دورة التوزيع أولًا');

 snapshot=capture(d,data.area==='غير محدد'?'—':data.area,today(),identitySnapshot());
 options=loadOptions(snapshot);
 opened=true;host.hidden=false;document.body.classList.add('gaz-preview-open');
 document.querySelector('main').inert=true;document.querySelector('body>header').inert=true;
 document.getElementById('rafdNav').inert=true;
 id('gazPreviewTitle').textContent='معاينة — '+titleOf(snapshot);
 status.textContent='جارٍ تجهيز ملف PDF…';controls(false);prepared=null;
 try{history.pushState({...history.state,gazDocumentPreview:true},'');historyOwned=true}catch{}
 requestAnimationFrame(()=>{if(opened)fit()});document.fonts.ready.then(()=>{if(opened)zoom(scale)});
 id(intent==='print'?'gazPreviewPrint':intent==='share'?'gazPreviewShare':intent==='export'?'gazPreviewMore':'gazPreviewClose').focus({preventScroll:true});
 refreshReport();
}
async function nativeDocument(file,intent){try{const bytes=new Uint8Array(await file.arrayBuffer());if(!opened||!permitted())return;const encoded=b64(bytes);if(intent==='print')window.GazNative.printDocument(file.name,encoded);else if(intent==='share')window.GazNative.shareDocument(file.name,'application/pdf',encoded);else window.GazNative.exportDocument(file.name,'application/pdf',encoded)}catch(e){alert('تعذر تجهيز الملف للجوال')} }
function download(file){if(window.GazNative){nativeDocument(file,'export');return;}const url=URL.createObjectURL(file);const a=document.createElement('a');a.href=url;a.download=file.name;document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),30000)}
id('gazPreviewPrint').onclick=async()=>{if(!opened||!permitted())return;await document.fonts.ready;if(opened&&permitted()){if(window.GazNative){if(prepared)nativeDocument(prepared,'print');else alert('انتظر اكتمال تجهيز PDF')}else window.print()}};
id('gazPreviewExport').onclick=()=>{if(opened&&permitted()&&prepared){download(prepared);closePreviewMore()}};
id('gazPreviewShare').onclick=async()=>{if(!opened||!permitted()||!prepared)return;const file=prepared;if(window.GazNative){nativeDocument(file,'share');return;}try{if(navigator.share&&navigator.canShare?.({files:[file]})){await navigator.share({files:[file],title:titleOf(snapshot)});return}}catch(e){if(e.name==='AbortError')return}download(file);alert('المشاركة المباشرة غير متاحة في هذا المتصفح. تم تنزيل ملف PDF، ويمكنك إرفاقه يدويًا.')};
for(const [button,intent] of [['previewBtn','preview'],['printBtn','print'],['sharePdfBtn','share'],['exportPdfBtn','export']])id(button).onclick=()=>openPreview(intent);
})();
