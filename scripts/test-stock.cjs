'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const html=fs.readFileSync('index.html','utf8'),mirror=fs.readFileSync('docs/index.html','utf8');
assert.equal(html,mirror,'Web and Android UI must match');
for(const name of ['stockTotal','stockWarningAt','distStockStatus'])assert.ok(html.includes('id="'+name+'"'),name+' missing');
const start=html.indexOf('function bagStock(c){'),end=html.indexOf('function suggestedAid(c,f)',start);
assert.ok(start>0&&end>start,'inventory functions absent');
const code=html.slice(start,end),messages=[];
const state={
  data:{deliveries:[{c:'a',f:'done',q:6},{c:'other',f:'not',q:50}]},
  motherAllocations:[
    {cycle:'a',family:'done',maxQty:6,returned:false},
    {cycle:'a',family:'reserved',maxQty:3,returned:false},
    {cycle:'a',family:'released',maxQty:5,returned:true},
    {cycle:'other',family:'remote',maxQty:5,returned:false}
  ],
  alert:msg=>messages.push(msg)
};
vm.runInNewContext(code,state);
const c={id:'a',stockTotal:10,stockWarningAt:2};
const x=state.bagStock(c);
assert.equal(x.total,10);
assert.equal(x.delivered,6);
assert.equal(x.reserved,3);
assert.equal(x.available,1);
assert.ok(state.bagStockText(c).includes('⚠️'));
assert.equal(state.bagCheck(c,1),true);
assert.equal(state.bagCheck(c,2),false);
assert.equal(messages.length,1);
assert.ok(state.bagStockText({...c,stockTotal:9}).includes('⛔'));
assert.equal(state.bagCheck({...c,stockTotal:null},1),false);
assert.equal(state.bagCheck({...c,stockTotal:20},11),true);
assert.equal(state.bagCheck({...c,stockTotal:20},12),false);
console.log('Rafd stock UI: delivered/reserved/returned, warning and hard-stop PASSED');
