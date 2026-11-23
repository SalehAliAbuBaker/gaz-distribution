
(()=>{'use strict';
const id=x=>document.getElementById(x);
const LIB='./vendor/ratib-pdfmake-rtl-0.2.23.js';
let enginePromise,fontsPromise,opened=false,token=0,prepared=null,snapshot=null,scale=1,pinch=null,historyOwned=false;
const plain=v=>String(v??'').replace(/[\u061C\u200E\u200F\u202A-\u202E\u2066-\u2069]/g,'').trim();
function capture(d,area,issued,identity={}){return {identity:{entity:plain(identity.entity),letterhead:plain(identity.letterhead),address:plain(identity.address),signatures:identity.signatures!==false,preparedRole:plain(identity.preparedRole)||'إعداد المختص',preparedName:plain(identity.preparedName),approvedRole:plain(identity.approvedRole)||'اعتماد المسؤول',approvedName:plain(identity.approvedName)},cycle:{title:plain(d.c.title),docNo:plain(d.c.docNo)||'-',date:plain(d.c.date),donor:plain(d.c.donor)||'-',details:plain(d.c.details)||'-',valueKind:plain(d.c.valueKind)||'none',unitMinor:Number(d.c.unitMinor)||0,currency:plain(d.c.currency)||'YER',stockTotal:d.c.stockTotal==null?null:Number(d.c.stockTotal)},area:plain(area),issued,mode:d.mode,rows:d.rows.map(r=>({name:plain(r.f.name),home:(plain(r.f.house)||'—')+' / '+(plain(r.f.square)||'—')+' / '+plain(r.f.area||area),members:r.f.members==null||String(r.f.members).trim()===''?'غير محدد':plain(r.f.members),category:plain(r.f.categories)||'-',quantity:Number(r.d?.q)||0,amountMinor:Number.isSafeInteger(r.d?.amountMinor)?r.d.amountMinor:null,valueKind:plain(r.d?.valueKind),currency:plain(r.d?.currency)}))}}
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
const metaRows=s=>[['رقم المستند',s.cycle.docNo,'تاريخ الحقيبة',s.cycle.date],['اسم الحقيبة',s.cycle.title,'المسؤول / المورد',s.cycle.donor],['نوع العطية',s.cycle.valueKind==='cash'?'نقدية':s.cycle.valueKind==='estimated'?'عينية بقيمة مقابلة':'عينية',''+(s.cycle.valueKind==='none'?'إجمالي الحقيبة':'قيمة العطية'),s.cycle.valueKind==='none'?(s.cycle.stockTotal??'غير محدد'):s.cycle.unitMinor?moneyFormat(s.cycle.unitMinor,s.cycle.currency):'—'],['عدد الأسر',String(s.rows.length),'تاريخ الإصدار',s.issued]];
function visibleColumns(s){
 const category=s.rows.some(r=>r.category&&r.category!=='-'&&r.category!=='—');
 const value=s.cycle.valueKind!=='none'||s.rows.some(r=>r.amountMinor!=null);
 return [
  {key:'number',title:'م',width:27,align:'center',get:(r,i)=>String(i+1)},
  {key:'name',title:'المسجل',width:140,get:r=>r.name},
  {key:'address',title:'رابط الأسرة / المربع / المنطقة',width:'*',get:r=>r.home},
  {key:'members',title:'الأفراد',width:52,align:'center',get:r=>r.members},
  ...(category?[{key:'category',title:'الفئة',width:55,get:r=>r.category}]:[]),
  {key:'quantity',title:'الكمية',width:47,align:'center',get:r=>r.quantity?String(r.quantity):'—'},
  ...(value?[{key:'value',title:'القيمة النقدية / المقابلة',width:111,get:r=>r.amountMinor!=null?moneyFormat(r.amountMinor,r.currency):'—'}]:[])
 ];
}
const filename=s=>('رفد_كشف_توزيع_المساعدات_'+s.cycle.docNo+'_'+s.cycle.title).replace(/[\\/:*?"<>|\s]+/g,'_').slice(0,160)+'.pdf';
function definition(s){
 const cell=(v,extra={})=>({text:String(v??''),alignment:'right',...extra});
 const grid={hLineWidth:()=>.5,vLineWidth:()=>.5,hLineColor:()=> '#94a3b8',vLineColor:()=> '#94a3b8',paddingLeft:()=>4,paddingRight:()=>4,paddingTop:()=>4,paddingBottom:()=>4};
 const meta=metaRows(s).map(row=>[cell(row[3]),cell(row[2],{bold:true,fillColor:'#f1f5f9'}),cell(row[1]),cell(row[0],{bold:true,fillColor:'#f1f5f9'})]);
 // RTL table columns are reversed for pdfmake's visual left-to-right column order.
 if(s.cycle.details&&s.cycle.details!=='-'&&s.cycle.details!=='—')
  meta.splice(2,0,[cell(s.cycle.details,{colSpan:3}),{}, {},cell('التفاصيل',{bold:true,fillColor:'#f1f5f9'})]);
 const cols=visibleColumns(s).slice().reverse();
 const body=[
  cols.map(col=>cell(col.title,{bold:true,alignment:'center',fillColor:'#e2e8f0'})),
  ...s.rows.map((r,i)=>cols.map(col=>cell(col.get(r,i),{alignment:col.align||'right',bold:col.key==='quantity'})))
 ];
 if(!s.rows.length)body.push([cell('لا توجد أسر مطابقة',{colSpan:cols.length,alignment:'center'}),...cols.slice(1).map(()=>({}))]);
 body.push(cols.map(col=>cell(col.key==='number'?'إجمالي العطايا':col.key==='quantity'?totalOf(s):'',{bold:true,alignment:col.align||'right',fillColor:'#f1f5f9'})));
 for(const [label,amount] of totals(s)){
  body.push(cols.map(col=>cell(col.key==='number'?label:col.key==='value'?amount:'',{bold:true,fillColor:'#e5f4ed'})));
 }
 return {pageSize:'A4',pageOrientation:'landscape',pageMargins:[28,28,28,34],defaultStyle:{font:'Tajawal',fontSize:9,color:'#111827',alignment:'right',lineHeight:1.12},info:{title:titleOf(s),creator:'رفد'},content:[
 {text:'بسم الله الرحمن الرحيم',alignment:'center',bold:true,fontSize:12,margin:[0,0,0,3]},
 ...((s.identity.entity||s.identity.letterhead||s.identity.address)?[{columns:[{width:'*',text:s.identity.address,alignment:'left',fontSize:10},{width:'*',stack:[{text:s.identity.entity,bold:true,fontSize:12},{text:s.identity.letterhead,fontSize:10}],alignment:'right'}],columnGap:12,margin:[0,2,0,6]}]:[]),
 {text:titleOf(s),bold:true,alignment:'center',fontSize:17,margin:[0,2,0,9]},
 {table:{widths:['*',73,'*',73],body:meta,dontBreakRows:true},layout:grid,margin:[0,0,0,9]},
 {table:{headerRows:1,widths:cols.map(c=>c.width),body,dontBreakRows:true,keepWithHeaderRows:1},layout:grid,margin:[0,0,0,16]},
 ...(s.identity.signatures?[{unbreakable:true,table:{widths:['*','*'],body:[['approved','prepared'].map(k=>({stack:[{text:s.identity[k+'Role'],bold:true},{text:'الاسم: '+(s.identity[k+'Name']||'.....................................'),margin:[0,6,0,0]},{text:'التوقيع: ..................................',margin:[0,6,0,0]}],margin:7}))]},layout:grid}]:[])
 ],footer:(page,count)=>({text:'صفحة '+page+' من '+count,alignment:'center',fontSize:8,color:'#64748b',margin:[0,7,0,0]})};
}
function engine(){if(window.pdfMake?.createPdf)return Promise.resolve(window.pdfMake);if(!enginePromise)enginePromise=new Promise((resolve,reject)=>{let script=document.createElement('script');script.src=LIB;script.onload=()=>window.pdfMake?.createPdf?resolve(window.pdfMake):reject(Error('محرك PDF غير صالح'));script.onerror=()=>{script.remove();reject(Error('محرك PDF غير متاح، تأكد من اكتمال تحديث التطبيق'))};document.head.append(script)}).catch(e=>{enginePromise=null;throw e});return enginePromise}
function b64(bytes){let binary='';for(let i=0;i<bytes.length;i+=16384)binary+=String.fromCharCode(...bytes.subarray(i,i+16384));return btoa(binary)}
function loadFonts(){if(!fontsPromise)fontsPromise=Promise.all(['tajawal_regular.ttf','tajawal_bold.ttf'].map(async f=>{let r=await fetch('./'+f,{cache:'force-cache'});if(!r.ok)throw Error('تعذر تحميل خط تجوّل');let b=new Uint8Array(await r.arrayBuffer());if(b.length<10000||b[0]!==0||b[1]!==1)throw Error('خط PDF غير صالح');return [f,b64(b)]})).then(Object.fromEntries).catch(e=>{fontsPromise=null;throw e});return fontsPromise}
async function makePDF(s){let [pdf,vfs]=await Promise.all([engine(),loadFonts()]);pdf.vfs={...(pdf.vfs||{}),...vfs};pdf.fonts={...(pdf.fonts||{}),Tajawal:{normal:'tajawal_regular.ttf',bold:'tajawal_bold.ttf',italics:'tajawal_regular.ttf',bolditalics:'tajawal_bold.ttf'}};let blob=await new Promise((resolve,reject)=>{try{pdf.createPdf(definition(s)).getBlob(b=>b?.size>100?resolve(b):reject(Error('تعذر إنشاء PDF')))}catch(e){reject(e)}});if((await blob.slice(0,5).text())!=='%PDF-')throw Error('الملف الناتج ليس PDF صالحًا');return new File([blob],filename(s),{type:'application/pdf'})}
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
host.innerHTML='<div class="gaz-preview-head"><h2 id="gazPreviewTitle">معاينة الكشف</h2><button id="gazPreviewClose" class="muted" type="button">إغلاق ×</button></div><div class="gaz-preview-toolbar"><button id="gazPreviewPrint" type="button">طباعة</button><button id="gazPreviewShare" type="button" disabled>مشاركة</button><button id="gazPreviewMore" type="button" aria-expanded="false" aria-controls="gazPreviewExtras">المزيد</button><div id="gazPreviewExtras" hidden><button id="gazPreviewExport" type="button" disabled>تصدير PDF</button></div></div><div class="gaz-zoom"><button id="gazZoomMinus" type="button" aria-label="تصغير">−</button><output id="gazZoomValue">100%</output><button id="gazZoomPlus" type="button" aria-label="تكبير">+</button><button id="gazZoomFit" type="button">ملاءمة الشاشة</button></div><div id="gazPreviewStatus" role="status"></div><div id="gazPreviewViewport" tabindex="0"><div id="gazPreviewExtent"><article id="gazPreviewPaper"></article></div></div>';
document.body.append(host);
const more=id('gazPreviewMore'),extra=id('gazPreviewExtras');function closePreviewMore(){extra.hidden=true;more.setAttribute('aria-expanded','false')}more.onclick=()=>{extra.hidden=!extra.hidden;more.setAttribute('aria-expanded',String(!extra.hidden))};host.addEventListener('click',e=>{if(!more.contains(e.target)&&!extra.contains(e.target))closePreviewMore()});
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
function hide(){if(!opened)return;closePreviewMore();opened=false;token++;prepared=null;snapshot=null;host.hidden=true;paper.replaceChildren();document.body.classList.remove('gaz-preview-open');document.querySelector('main').inert=false;document.querySelector('body>header').inert=false;document.getElementById('rafdNav').inert=false;pinch=null;historyOwned=false}
function close(){let owned=historyOwned;hide();if(owned&&history.state?.gazDocumentPreview)history.back()}
id('gazPreviewClose').onclick=close;window.addEventListener('popstate',()=>{if(opened)hide()});document.addEventListener('keydown',e=>{if(opened&&e.key==='Escape'){e.preventDefault();close()}});
function openPreview(intent='preview'){
 if(opened)return;if(!permitted())return alert('ليس لديك صلاحية عرض الكشوف');const d=statement();if(!d)return alert('اختر دورة التوزيع أولًا');
 snapshot=capture(d,data.area==='غير محدد'?'—':data.area,today(),identitySnapshot());printRows(true);
 const clone=id('statementPaper').cloneNode(true);clone.querySelectorAll('.no-print,.noPrint,button,[data-act]').forEach(n=>n.remove());
 // Move only the clean snapshot into the preview, so actions and live edits never print.
 paper.replaceChildren(...clone.childNodes);if(d.rows.length>50)printRows();opened=true;host.hidden=false;document.body.classList.add('gaz-preview-open');document.querySelector('main').inert=true;document.querySelector('body>header').inert=true;document.getElementById('rafdNav').inert=true;id('gazPreviewTitle').textContent='معاينة — '+titleOf(snapshot);status.textContent='جارٍ تجهيز ملف PDF…';controls(false);prepared=null;
 try{history.pushState({...history.state,gazDocumentPreview:true},'');historyOwned=true}catch{}
 requestAnimationFrame(()=>{if(opened)fit()});document.fonts.ready.then(()=>{if(opened)zoom(scale)});
 id(intent==='print'?'gazPreviewPrint':intent==='share'?'gazPreviewShare':intent==='export'?'gazPreviewMore':'gazPreviewClose').focus({preventScroll:true});
 const mine=++token,s=snapshot;makePDF(s).then(file=>{if(!opened||mine!==token||!permitted())return;prepared=file;controls(true);status.textContent='جاهز للطباعة والمشاركة وتصدير PDF';}).catch(e=>{if(opened&&mine===token){status.textContent='تعذر تجهيز PDF. يمكنك الطباعة أو إغلاق المعاينة وإعادة فتحها.';console.error(e)}});
}
async function nativeDocument(file,intent){try{const bytes=new Uint8Array(await file.arrayBuffer());if(!opened||!permitted())return;const encoded=b64(bytes);if(intent==='print')window.GazNative.printDocument(file.name,encoded);else if(intent==='share')window.GazNative.shareDocument(file.name,'application/pdf',encoded);else window.GazNative.exportDocument(file.name,'application/pdf',encoded)}catch(e){alert('تعذر تجهيز الملف للجوال')} }
function download(file){if(window.GazNative){nativeDocument(file,'export');return;}const url=URL.createObjectURL(file);const a=document.createElement('a');a.href=url;a.download=file.name;document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),30000)}
id('gazPreviewPrint').onclick=async()=>{if(!opened||!permitted())return;await document.fonts.ready;if(opened&&permitted()){if(window.GazNative){if(prepared)nativeDocument(prepared,'print');else alert('انتظر اكتمال تجهيز PDF')}else window.print()}};
id('gazPreviewExport').onclick=()=>{if(opened&&permitted()&&prepared){download(prepared);closePreviewMore()}};
id('gazPreviewShare').onclick=async()=>{if(!opened||!permitted()||!prepared)return;const file=prepared;if(window.GazNative){nativeDocument(file,'share');return;}try{if(navigator.share&&navigator.canShare?.({files:[file]})){await navigator.share({files:[file],title:titleOf(snapshot)});return}}catch(e){if(e.name==='AbortError')return}download(file);alert('المشاركة المباشرة غير متاحة في هذا المتصفح. تم تنزيل ملف PDF، ويمكنك إرفاقه يدويًا.')};
for(const [button,intent] of [['previewBtn','preview'],['printBtn','print'],['sharePdfBtn','share'],['exportPdfBtn','export']])id(button).onclick=()=>openPreview(intent);
})();
