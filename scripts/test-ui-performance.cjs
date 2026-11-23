'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');

const root=path.resolve(__dirname,'..');
const html=fs.readFileSync(path.join(root,'index.html'),'utf8');
const mirrored=fs.readFileSync(path.join(root,'docs','index.html'),'utf8');
assert.equal(html,mirrored,'Android and Pages UI assets must stay identical');
for(const [index,match] of [...html.matchAll(/<script([^>]*)>([\s\S]*?)<\/script>/g)].entries()){
    if(/\bsrc\s*=/.test(match[1])||!match[2].trim())continue;
    new vm.Script(match[2],{filename:'index-inline-'+index+'.js'});
}
function extract(start,end){
    const i=html.indexOf(start),j=html.indexOf(end,i+start.length);
    assert.ok(i>=0&&j>i,'Missing UI function: '+start);
    return html.slice(i,j);
}
const calls=[];
const fields={};
function stamp(name){return ()=>{calls.push(name);};}
const ctx={
    activeUser:{username:'admin'},current:'home',deviceRole:'independent',pairRestricted:false,
    data:{families:[{id:'a',no:1,name:'أحمد'}],cycles:[],deliveries:[],n:1,area:'غير محدد'},
    homeSearchRows:null,motherAllocations:[],ef:null,
    document:{querySelectorAll:()=>[]},
    $:id=>fields[id]??(fields[id]={textContent:'',value:'',hidden:false}),
    can:()=>true,printIdentity:()=>({entity:''}),lookup:()=>null,
    renderHomeSearch:stamp('search'),renderDistributorHome:stamp('distributor'),
    allocationRows:()=>{calls.push('allocationRows');return [];},
    catOptions:stamp('categories'),squareOptions:stamp('squares'),
    familyList:stamp('familyList'),cycleList:stamp('cycleList'),dist:stamp('dist'),
    printRows:stamp('printRows'),reports:stamp('reports'),users:stamp('users'),
    opt:stamp('opt'),applyPermissions:stamp('permissions')
};
vm.runInNewContext(extract('function refresh(){',"\ndocument.addEventListener('click'"),ctx);
function verifyRoute(route,should,shouldNot){
    calls.length=0;ctx.current=route;ctx.refresh();
    for(const name of should)assert.ok(calls.includes(name),route+' must call '+name);
    for(const name of shouldNot)assert.ok(!calls.includes(name),route+' must not call '+name);
}
verifyRoute('home',['search'],['familyList','dist','printRows','reports','allocationRows']);
verifyRoute('families',['familyList','categories','squares'],['printRows','reports','dist','allocationRows']);
verifyRoute('cycles',['cycleList'],['familyList','dist','printRows','reports']);
verifyRoute('distribution',['dist','allocationRows'],['familyList','printRows','reports']);
verifyRoute('prints',['printRows','opt'],['familyList','dist','reports','allocationRows']);
verifyRoute('reports',['reports','opt'],['familyList','printRows','dist']);
verifyRoute('permissions',['users'],['familyList','printRows','reports']);
verifyRoute('settings',[],['familyList','printRows','reports','dist','allocationRows']);
verifyRoute('smartFamily',['allocationRows'],['printRows','reports','familyList']);
const listCtx={data:{families:[],deliveries:[]}};
for(let i=0;i<1000;i++){
    listCtx.data.families.push({id:String(i),no:i+1,name:'أسرة '+i,archived:i%47===0});
    if(i%2===0)listCtx.data.deliveries.push({c:'cycle1',f:String(i),q:1});
}
listCtx.data.deliveries.push({c:'other',f:'1',q:1});
vm.runInNewContext(extract('function rowsFor(c,mode){',"\nfunction printIdentity("),listCtx);
const cycle={id:'cycle1'};
const done=listCtx.rowsFor(cycle,'done');
const pending=listCtx.rowsFor(cycle,'pending');
const all=listCtx.rowsFor(cycle,'all');
assert.equal(done.length,500);
assert.equal(pending.length,listCtx.data.families.filter(f=>!f.archived&&Number(f.id)%2===1).length);
assert.equal(all.length,done.length+pending.length);
assert.ok(done.every(r=>r.d&&r.d.c==='cycle1'));
assert.ok(pending.every(r=>!r.d&&!r.f.archived));
assert.ok(extract('function visibleDist(){',"\nfunction distControls(").includes('new Set('),'Filtered distribution should index deliveries');
assert.ok(extract('function reports(){',"\nlet homeSearchRows=").includes('new Set('),'Reports should reuse one search index');
console.log('Rafd UI regression: syntax, screen-level rendering and 1000-family delivery filtering PASSED');
