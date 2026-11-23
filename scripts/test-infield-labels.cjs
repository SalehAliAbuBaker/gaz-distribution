'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const html=fs.readFileSync('index.html','utf8');
assert.equal(html,fs.readFileSync('docs/index.html','utf8'),'Web and Android pages must match');
const match=html.match(/<script id="rafdInfieldLabelsController">([\s\S]*?)<\/script>/);
assert(match,'Global in-field labels controller must be registered');
const handlers={document:{},window:{}};
function mockField(value,type='text'){
 const control={value,type};
 const classes=new Set(['rafd-field']);
 return {control,classList:{contains:name=>classes.has(name),toggle:(name,on)=>on?classes.add(name):classes.delete(name)},
  querySelector:sel=>control,
  matches:sel=>sel==='main .rafd-field',
  querySelectorAll:()=>[],
  get empty(){return classes.has('rafd-infield-empty')},
  closest:sel=>sel==='main .rafd-field'?this:null};
}
const textField=mockField(''),selectField=mockField('','select'),memoField=mockField('','textarea'),dateField=mockField('','date');
let fields=[textField,selectField,memoField,dateField];
const document={querySelectorAll:()=>fields,addEventListener:(name,fn)=>{handlers.document[name]=fn}};
const window={addEventListener:(name,fn)=>{handlers.window[name]=fn}};
vm.runInNewContext(match[1],{document,window,requestAnimationFrame:fn=>fn()},{filename:'rafd-infield-labels'});
assert(fields.every(f=>f.empty),'All blank field types should display a label');
const emit=(name,field)=>handlers.document[name]({target:{closest:()=>field}});
textField.control.value='عبدالله';emit('input',textField);
assert(!textField.empty,'A nonempty text field must hide its label');
textField.control.value='';emit('input',textField);
assert(textField.empty,'Cleared text field must restore its label');
selectField.control.value='الروضة';emit('change',selectField);
assert(!selectField.empty,'Selecting a dropdown value hides its label');
memoField.control.value='ملاحظة';emit('input',memoField);
assert(!memoField.empty,'Typing in notes hides the label');
dateField.control.value='2026-11-23';emit('change',dateField);
assert(!dateField.empty,'Date selection hides the label');
textField.control.value='قيمة محملة برمجيًا';
handlers.document.click({target:{closest:()=>true}});
assert(!textField.empty,'Programmatically populated field must update after navigation');
const dynamic=mockField('افتراضي');fields=[...fields,dynamic];
handlers.document.click({target:{closest:()=>true}});
assert(!dynamic.empty,'Newly inserted controls must be refreshed');
dynamic.control.value='';emit('focusout',dynamic);
assert(dynamic.empty,'Dynamic control must restore empty title after clearing');
assert(html.includes('main .rafd-field > .rafd-inline-label'));
assert(html.includes('main .rafd-field:not(:focus-within)'));
assert(html.includes(':has(input:placeholder-shown)'));
assert(html.includes('select option:checked:not([value=""])'));
assert(html.includes('visibility:hidden!important'));
console.log('Rafd global field labels: text, numbers, selection, date, notes and programmatic updates PASSED');
