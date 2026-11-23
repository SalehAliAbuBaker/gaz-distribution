'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const source=fs.readFileSync('gaz-document-preview.js','utf8');
const start=source.indexOf('const id='),end=source.indexOf('window.GazDocumentPdf=');
assert(start>=0&&end>start,'Report renderer source must be discoverable');
const moduleSource=source.slice(start,end);
const {capture,definition,loadOptions,visibleColumns,defaultOptions,columnWidths,summaryRows,metaItems,htmlSafe,titleOf}=new Function(
 'window','document','localStorage',moduleSource+
 '\nreturn {capture,definition,loadOptions,visibleColumns,defaultOptions,columnWidths,summaryRows,metaItems,htmlSafe,titleOf};'
)({}, {}, {getItem:()=>null,setItem:()=>{}});
const bag={c:{title:'حقيبة تجريبية',docNo:'2026-0001',date:'2026-11-23',donor:'المسؤول',details:'غاز',valueKind:'none',unitMinor:0,currency:'YER',stockTotal:8},
 mode:'done',rows:[{f:{name:'أحمد علي',no:17,identityNo:'123456',phone:'777000000',house:'المنزل',square:'المربع',area:'المنطقة',members:2,categories:'الفئة'},d:{q:1,amountMinor:null}}]};
const snapshot=capture(bag,'المنطقة','2026-11-23',{entity:'مؤسسة',signatures:true});
const settings=loadOptions(snapshot);
assert.equal(snapshot.rows[0].registerNo,'17');
assert.equal(snapshot.rows[0].phone,'777000000');
assert.equal(snapshot.rows[0].identityNo,'123456');
let doc=definition(snapshot,settings);
assert.equal(doc.pageSize,'A4');
assert.equal(doc.pageOrientation,'portrait');
let table=doc.content.find(c=>c.table?.headerRows===1);
assert(table,'Distribution table must appear in portrait report');
assert.equal(table.table.body[0].length,7);
assert(table.table.body[1].some(c=>c.text==='أحمد علي'));
assert(!table.table.body.some(row=>row.some(c=>String(c.text).includes('إجمالي العطايا'))),'Total label must not be squeezed into numbered column');
const summaryBlock=doc.content.find(c=>c.unbreakable&&c.table?.widths?.[1]===175);
assert(summaryBlock,'Full-width PDF summary must appear below the beneficiaries table');
assert.equal(summaryBlock.table.body[0][0].text,'1');
assert.equal(summaryBlock.table.body[0][1].text,'إجمالي العطايا المصروفة');
assert(doc.content.indexOf(summaryBlock)>doc.content.indexOf(doc.content.find(c=>c.table?.headerRows===1)));
assert(doc.content.some(c=>c.unbreakable),'Signature block must be included by default');
const selected={...settings,number:false,registerNo:true,phone:true,identityNo:true,area:false,square:false,signatures:false,docNo:false,donor:false};
doc=definition(snapshot,selected);
table=doc.content.find(c=>c.table?.headerRows===1);
assert.equal(table.table.body[0].length,7,'Visible columns must reflect selected fields');
assert.equal(doc.content.filter(c=>c.unbreakable).length,1,'The full-width summary remains while signatures are disabled');
const header=table.table.body[0].map(c=>c.text);
assert(header.includes('رقم المسجل')&&header.includes('الهاتف')&&header.includes('الهوية'));
assert(!header.includes('المنطقة')&&!header.includes('م'));
const all=Object.fromEntries(Object.keys(defaultOptions()).map(k=>[k,true]));
const cols=visibleColumns(snapshot,all),widths=columnWidths(cols);
assert(widths.every(Number.isFinite));
assert(widths.reduce((n,v)=>n+v,0)+6*cols.length<=547,'All selected fields must fit A4 portrait content width');
const paper={style:{setProperty(){}},innerHTML:''};
const renderSource=source.slice(source.indexOf('function renderReportPaper('),source.indexOf('function refreshReport('));
const renderReportPaper=new Function('paper','visibleColumns','metaItems','summaryRows','htmlSafe','titleOf',
 renderSource+'\nreturn renderReportPaper;')(paper,visibleColumns,metaItems,summaryRows,htmlSafe,titleOf);
renderReportPaper(snapshot,settings);
assert.match(paper.innerHTML,/<\/tbody><\/table><div class="gaz-report-summary"/,'Summary must be separate from narrow table cells');
assert.match(paper.innerHTML,/<strong>إجمالي العطايا المصروفة<\/strong><b>1<\/b>/);
assert(!paper.innerHTML.includes('class="gaz-report-total"'),'Legacy narrow total row must be removed');
const withoutTotals={...settings,totals:false};
assert(!definition(snapshot,withoutTotals).content.some(c=>c.table?.widths?.[1]===175),'Hiding totals removes summary from PDF');
renderReportPaper(snapshot,withoutTotals);
assert(!paper.innerHTML.includes('gaz-report-summary'),'Hiding totals removes summary from preview');
console.log('A4 portrait statement and full-width total summary: passed');
