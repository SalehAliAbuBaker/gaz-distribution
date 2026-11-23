'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const source=fs.readFileSync('gaz-document-preview.js','utf8');
const start=source.indexOf('const id='),end=source.indexOf('window.GazDocumentPdf=');
assert(start>=0&&end>start,'Report renderer source must be discoverable');
const moduleSource=source.slice(start,end);
const {capture,definition,loadOptions,visibleColumns,defaultOptions,columnWidths}=new Function(
 'window','document','localStorage',moduleSource+
 '\nreturn {capture,definition,loadOptions,visibleColumns,defaultOptions,columnWidths};'
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
assert(table.table.body.some(row=>row.some(c=>c.text==='إجمالي العطايا')));
assert(doc.content.some(c=>c.unbreakable),'Signature block must be included by default');
const selected={...settings,number:false,registerNo:true,phone:true,identityNo:true,area:false,square:false,signatures:false,docNo:false,donor:false};
doc=definition(snapshot,selected);
table=doc.content.find(c=>c.table?.headerRows===1);
assert.equal(table.table.body[0].length,7,'Visible columns must reflect selected fields');
assert(!doc.content.some(c=>c.unbreakable),'Disabled signatures must not print');
const header=table.table.body[0].map(c=>c.text);
assert(header.includes('رقم المسجل')&&header.includes('الهاتف')&&header.includes('الهوية'));
assert(!header.includes('المنطقة')&&!header.includes('م'));
const all=Object.fromEntries(Object.keys(defaultOptions()).map(k=>[k,true]));
const cols=visibleColumns(snapshot,all),widths=columnWidths(cols);
assert(widths.every(Number.isFinite));
assert(widths.reduce((n,v)=>n+v,0)+6*cols.length<=547,'All selected fields must fit A4 portrait content width');
console.log('A4 portrait statement options: passed');
