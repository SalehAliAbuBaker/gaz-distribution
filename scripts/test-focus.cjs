'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const html=fs.readFileSync('index.html','utf8'),
      pdf=fs.readFileSync('gaz-document-preview.js','utf8');
assert.equal(html,fs.readFileSync('docs/index.html','utf8'));
assert.equal(pdf,fs.readFileSync('docs/gaz-document-preview.js','utf8'));
for(const [index,match] of [...html.matchAll(/<script([^>]*)>([\s\S]*?)<\/script>/g)].entries()){
 if(/\bsrc\s*=/.test(match[1])||!match[2].trim())continue;
 new vm.Script(match[2],{filename:'inline-'+index});
}
new vm.Script(pdf,{filename:'gaz-document-preview.js'});
function extract(source,start,end){
 const i=source.indexOf(start),j=source.indexOf(end,i+start.length);
 assert.ok(i>=0&&j>i,start+' missing');
 return source.slice(i,j);
}
// The snapshot and pdfMake document definition must suppress genuinely empty columns.
const pdfCtx={};
vm.runInNewContext(extract(pdf,'const OPTION_GROUPS=','function engine()')+
  '\nthis.testPdf={definition,visibleColumns,capture,defaultOptions};',pdfCtx);
function sample(n=8,kind='none',categories=''){
 return {identity:{signatures:false},area:'test',issued:'2026-11-23',mode:'done',
   cycle:{title:'حقيبة اختبار',date:'2026-11-23',docNo:'2026-0001',donor:'—',details:'غاز',valueKind:kind,unitMinor:kind==='none'?0:100,currency:'YER',stockTotal:500},
   rows:Array.from({length:n},(_,i)=>({name:'مستفيد '+i,house:'الحي',square:'المربع',area:'المنطقة',members:'غير محدد',
     category:categories,quantity:1,amountMinor:kind==='none'?null:100,currency:'YER',valueKind:kind}))};
}
function pdfTable(s,o){
 return pdfCtx.testPdf.definition(s,o).content.find(x=>x.table?.headerRows===1).table;
}
let table=pdfTable(sample());
assert.equal(pdfCtx.testPdf.visibleColumns(sample(),pdfCtx.testPdf.defaultOptions()).length,7);
assert.equal(table.widths.length,7);
assert.ok(table.body.every(row=>row.length===7));
assert.equal(table.body.at(-1).length,7);
const withFields=sample(8,'cash','أولوية');
const selected={...pdfCtx.testPdf.defaultOptions(),category:true,value:true};
table=pdfTable(withFields,selected);
assert.equal(table.widths.length,9);
assert.ok(table.body.every(row=>row.length===9));
assert.ok(table.body[0].some(x=>x.text==='الفئة'));
assert.ok(table.body[0].some(x=>String(x.text).includes('القيمة')));
// Android HTML report keeps only 50 rows in ordinary view, while full PDF view is complete.
const printed={};
const fields={statementPaper:{innerHTML:''}};
const rows=Array.from({length:120},(_,i)=>({f:{id:String(i),name:'مسجل اختبار '+i,house:'—',square:'—',area:'الحي',members:2,categories:''},d:{q:1,amountMinor:null}}));
const ctx={
 can:()=>true,$:id=>fields[id]??(fields[id]={}),statement:()=>({c:{title:'تجريبية',valueKind:'none',docNo:'1',date:'2026-11-23',stockTotal:120},rows,mode:'done'}),
 identitySnapshot:()=>({}),statementHeader:()=>'HEADER',today:()=> '2026-11-23',latinDigits:x=>x,
 esc:x=>String(x),bagStockText:()=>'',moneyLabel:()=>'',moneyText:()=>'',membersText:f=>String(f.members),
 rowAmount:()=>'',financialTotal:()=>'',statementSign:()=>'',btn:()=>'',applyPermissions:()=>{}
};
vm.runInNewContext(extract(html,'function printRows(full=false){','function statementText(){'),ctx);
ctx.printRows();
assert.match(fields.statementPaper.innerHTML,/أول 50 أسرة/);
assert.ok(fields.statementPaper.innerHTML.includes('مسجل اختبار 49'));
assert.ok(!fields.statementPaper.innerHTML.includes('مسجل اختبار 100'));
assert.ok(!fields.statementPaper.innerHTML.includes('<th>الفئة</th>'));
ctx.printRows(true);
assert.ok(fields.statementPaper.innerHTML.includes('مسجل اختبار 100'));
assert.ok(!fields.statementPaper.innerHTML.includes('أول 50 أسرة'));
assert.ok(html.includes('<details id="familyMoreTools"'));
assert.ok(html.includes('<details id="familyFiltersPanel"'));
assert.ok(html.includes('rafd-inline-tools'));
console.log('Rafd focus + report tests: selectable PDF columns, 50-row lightweight screen, full print and collapsed controls PASSED');
