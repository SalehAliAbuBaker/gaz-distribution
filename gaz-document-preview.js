
(()=>{'use strict';
const id=x=>document.getElementById(x);
const LIB='./vendor/ratib-pdfmake-rtl-0.2.23.js';
let enginePromise,fontsPromise,opened=false,token=0,prepared=null,snapshot=null,scale=1,pinch=null,historyOwned=false;
const plain=v=>String(v??'').replace(/[\u061C\u200E\u200F\u202A-\u202E\u2066-\u2069]/g,'').trim();
function capture(d,area,issued){return {cycle:{title:plain(d.c.title),docNo:plain(d.c.docNo)||'-',date:plain(d.c.date),donor:plain(d.c.donor)||'-',details:plain(d.c.details)||'-'},area:plain(area),issued,mode:d.mode,rows:d.rows.map(r=>({name:plain(r.f.name),home:(plain(r.f.house)||'—')+' / '+(plain(r.f.square)||'—')+' / '+plain(r.f.area||area),members:plain(r.f.members),category:plain(r.f.categories)||'-',quantity:Number(r.d?.q)||0}))}}
const titleOf=s=>s.mode==='pending'?'كشف الأسر التي لم تستلم':'كشف توزيع الغاز';
const totalOf=s=>s.rows.reduce((sum,r)=>sum+r.quantity,0);
const metaRows=s=>[['رقم المستند',s.cycle.docNo,'تاريخ الدورة',s.cycle.date],['مسمى الدورة',s.cycle.title,'المسؤول / المورد',s.cycle.donor],['عدد الأسر',String(s.rows.length),'تاريخ الإصدار',s.issued]];
const heads=['م','المستفيد','رابط الأسرة/رقم المنزل / المربع / المنطقة','الأفراد','الفئة','الكمية'];
const fields=(r,i)=>[String(i+1),r.name,r.home,r.members,r.category,r.quantity?String(r.quantity):'—'];
const filename=s=>('كشف_توزيع_الغاز_'+s.cycle.docNo+'_'+s.cycle.title).replace(/[\\/:*?"<>|\s]+/g,'_').slice(0,160)+'.pdf';
function definition(s){
 const cell=(v,extra={})=>({text:String(v??''),alignment:'right',...extra});
 const grid={hLineWidth:()=>.5,vLineWidth:()=>.5,hLineColor:()=> '#94a3b8',vLineColor:()=> '#94a3b8',paddingLeft:()=>4,paddingRight:()=>4,paddingTop:()=>4,paddingBottom:()=>4};
 const meta=metaRows(s).map(row=>[cell(row[3]),cell(row[2],{bold:true,fillColor:'#f1f5f9'}),cell(row[1]),cell(row[0],{bold:true,fillColor:'#f1f5f9'})]);
 // RTL table columns are reversed for pdfmake's visual left-to-right column order.
 meta.splice(2,0,[cell(s.cycle.details,{colSpan:3}),{}, {},cell('التفاصيل',{bold:true,fillColor:'#f1f5f9'})]);
 const body=[heads.slice().reverse().map(v=>cell(v,{bold:true,alignment:'center',fillColor:'#e2e8f0'})),...s.rows.map((r,i)=>fields(r,i).reverse().map((v,j)=>cell(v,{alignment:[0,2,5].includes(j)?'center':'right',bold:j===0})))];
 if(!s.rows.length)body.push([cell('لا توجد أسر مطابقة',{colSpan:6,alignment:'center'}),{},{},{},{},{}]);
 body.push([cell(totalOf(s),{bold:true,alignment:'center',fillColor:'#f1f5f9'}),cell('إجمالي الأسطوانات',{colSpan:5,bold:true,fillColor:'#f1f5f9'}),{},{},{},{}]);
 return {pageSize:'A4',pageOrientation:'portrait',pageMargins:[28,28,28,34],defaultStyle:{font:'Tajawal',fontSize:9,color:'#111827',alignment:'right',lineHeight:1.12},info:{title:titleOf(s),creator:'توزيع الغاز'},content:[
 {text:'بسم الله الرحمن الرحيم',alignment:'center',bold:true,fontSize:12,margin:[0,0,0,3]},
 {text:s.area,alignment:'center',fontSize:10,color:'#475569'},
 {text:titleOf(s),bold:true,alignment:'center',fontSize:17,margin:[0,2,0,9]},
 {table:{widths:['*',73,'*',73],body:meta,dontBreakRows:true},layout:grid,margin:[0,0,0,9]},
 {table:{headerRows:1,widths:[45,73,42,'*','*',24],body,dontBreakRows:true,keepWithHeaderRows:1},layout:grid,margin:[0,0,0,16]},
 {unbreakable:true,table:{widths:['*','*'],body:[[{stack:[{text:'2. اعتمد من:',bold:true},{text:'الاسم: .....................................',margin:[0,6,0,0]},{text:'التوقيع: ..................................',margin:[0,6,0,0]}],margin:7},{stack:[{text:'1. إعداد المختص:',bold:true},{text:'الاسم: .....................................',margin:[0,6,0,0]},{text:'التوقيع: ..................................',margin:[0,6,0,0]}],margin:7}]]},layout:grid}
 ],footer:(page,count)=>({text:'صفحة '+page+' من '+count,alignment:'center',fontSize:8,color:'#64748b',margin:[0,7,0,0]})};
}
function engine(){if(window.pdfMake?.createPdf)return Promise.resolve(window.pdfMake);if(!enginePromise)enginePromise=new Promise((resolve,reject)=>{let script=document.createElement('script');script.src=LIB;script.onload=()=>window.pdfMake?.createPdf?resolve(window.pdfMake):reject(Error('محرك PDF غير صالح'));script.onerror=()=>{script.remove();reject(Error('محرك PDF غير متاح، تأكد من اكتمال تحديث التطبيق'))};document.head.append(script)}).catch(e=>{enginePromise=null;throw e});return enginePromise}
function b64(bytes){let binary='';for(let i=0;i<bytes.length;i+=16384)binary+=String.fromCharCode(...bytes.subarray(i,i+16384));return btoa(binary)}
function loadFonts(){if(!fontsPromise)fontsPromise=Promise.all(['tajawal_regular.ttf','tajawal_bold.ttf'].map(async f=>{let r=await fetch('./'+f,{cache:'force-cache'});if(!r.ok)throw Error('تعذر تحميل خط تجوّل');let b=new Uint8Array(await r.arrayBuffer());if(b.length<10000||b[0]!==0||b[1]!==1)throw Error('خط PDF غير صالح');return [f,b64(b)]})).then(Object.fromEntries).catch(e=>{fontsPromise=null;throw e});return fontsPromise}
async function makePDF(s){let [pdf,vfs]=await Promise.all([engine(),loadFonts()]);pdf.vfs={...(pdf.vfs||{}),...vfs};pdf.fonts={...(pdf.fonts||{}),Tajawal:{normal:'tajawal_regular.ttf',bold:'tajawal_bold.ttf',italics:'tajawal_regular.ttf',bolditalics:'tajawal_bold.ttf'}};let blob=await new Promise((resolve,reject)=>{try{pdf.createPdf(definition(s)).getBlob(b=>b?.size>100?resolve(b):reject(Error('تعذر إنشاء PDF')))}catch(e){reject(e)}});if((await blob.slice(0,5).text())!=='%PDF-')throw Error('الملف الناتج ليس PDF صالحًا');return new File([blob],filename(s),{type:'application/pdf'})}
window.GazDocumentPdf={capture,definition,makePDF,filename};
const host=document.createElement('div');host.id='gazPreview';host.hidden=true;host.setAttribute('role','dialog');host.setAttribute('aria-modal','true');host.setAttribute('aria-labelledby','gazPreviewTitle');
host.innerHTML='<div class="gaz-preview-head"><h2 id="gazPreviewTitle">معاينة الكشف</h2><button id="gazPreviewClose" class="muted" type="button">إغلاق ×</button></div><div class="gaz-preview-toolbar"><button id="gazPreviewPrint" type="button">طباعة</button><button id="gazPreviewShare" type="button" disabled>مشاركة</button><button id="gazPreviewExport" type="button" disabled>تصدير PDF</button></div><div class="gaz-zoom"><button id="gazZoomMinus" type="button" aria-label="تصغير">−</button><output id="gazZoomValue">100%</output><button id="gazZoomPlus" type="button" aria-label="تكبير">+</button><button id="gazZoomFit" type="button">ملاءمة الشاشة</button></div><div id="gazPreviewStatus" role="status"></div><div id="gazPreviewViewport" tabindex="0"><div id="gazPreviewExtent"><article id="gazPreviewPaper"></article></div></div>';
document.body.append(host);
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
function hide(){if(!opened)return;opened=false;token++;prepared=null;snapshot=null;host.hidden=true;paper.replaceChildren();document.body.classList.remove('gaz-preview-open');document.querySelector('main').inert=false;document.querySelector('body>header').inert=false;pinch=null;historyOwned=false}
function close(){let owned=historyOwned;hide();if(owned&&history.state?.gazDocumentPreview)history.back()}
id('gazPreviewClose').onclick=close;window.addEventListener('popstate',()=>{if(opened)hide()});document.addEventListener('keydown',e=>{if(opened&&e.key==='Escape'){e.preventDefault();close()}});
function openPreview(intent='preview'){
 if(opened)return;if(!permitted())return alert('ليس لديك صلاحية عرض الكشوف');const d=statement();if(!d)return alert('اختر دورة التوزيع أولًا');
 snapshot=capture(d,data.area,today());printRows();
 const clone=id('statementPaper').cloneNode(true);clone.querySelectorAll('.no-print,.noPrint,button,[data-act]').forEach(n=>n.remove());
 // Move only the clean snapshot into the preview, so actions and live edits never print.
 paper.replaceChildren(...clone.childNodes);opened=true;host.hidden=false;document.body.classList.add('gaz-preview-open');document.querySelector('main').inert=true;document.querySelector('body>header').inert=true;id('gazPreviewTitle').textContent='معاينة — '+titleOf(snapshot);status.textContent='جارٍ تجهيز ملف PDF…';controls(false);prepared=null;
 try{history.pushState({...history.state,gazDocumentPreview:true},'');historyOwned=true}catch{}
 requestAnimationFrame(()=>{if(opened)fit()});document.fonts.ready.then(()=>{if(opened)zoom(scale)});
 id(intent==='print'?'gazPreviewPrint':intent==='share'?'gazPreviewShare':intent==='export'?'gazPreviewExport':'gazPreviewClose').focus({preventScroll:true});
 const mine=++token,s=snapshot;makePDF(s).then(file=>{if(!opened||mine!==token||!permitted())return;prepared=file;controls(true);status.textContent='جاهز للطباعة والمشاركة وتصدير PDF';}).catch(e=>{if(opened&&mine===token){status.textContent='تعذر تجهيز PDF. يمكنك الطباعة أو إغلاق المعاينة وإعادة فتحها.';console.error(e)}});
}
async function nativeDocument(file,intent){try{const bytes=new Uint8Array(await file.arrayBuffer());if(!opened||!permitted())return;const encoded=b64(bytes);if(intent==='print')window.GazNative.printDocument(file.name,encoded);else if(intent==='share')window.GazNative.shareDocument(file.name,'application/pdf',encoded);else window.GazNative.exportDocument(file.name,'application/pdf',encoded)}catch(e){alert('تعذر تجهيز الملف للجوال')} }
function download(file){if(window.GazNative){nativeDocument(file,'export');return;}const url=URL.createObjectURL(file);const a=document.createElement('a');a.href=url;a.download=file.name;document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),30000)}
id('gazPreviewPrint').onclick=async()=>{if(!opened||!permitted())return;await document.fonts.ready;if(opened&&permitted()){if(window.GazNative){if(prepared)nativeDocument(prepared,'print');else alert('انتظر اكتمال تجهيز PDF')}else window.print()}};
id('gazPreviewExport').onclick=()=>{if(opened&&permitted()&&prepared)download(prepared)};
id('gazPreviewShare').onclick=async()=>{if(!opened||!permitted()||!prepared)return;const file=prepared;if(window.GazNative){nativeDocument(file,'share');return;}try{if(navigator.share&&navigator.canShare?.({files:[file]})){await navigator.share({files:[file],title:titleOf(snapshot)});return}}catch(e){if(e.name==='AbortError')return}download(file);alert('المشاركة المباشرة غير متاحة في هذا المتصفح. تم تنزيل ملف PDF، ويمكنك إرفاقه يدويًا.')};
for(const [button,intent] of [['previewBtn','preview'],['printBtn','print'],['sharePdfBtn','share'],['exportPdfBtn','export']])id(button).onclick=()=>openPreview(intent);
})();
