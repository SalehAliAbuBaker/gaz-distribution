// Gaz distribution authority protocol v1. No network transport or UI here.
// The caller MUST atomically persist the returned state before transmitting its packet.
const VERSION=1;
const clone=value=>JSON.parse(JSON.stringify(value));
const fail=message=>{throw new Error(message)};
const ok=(value,message)=>{if(!value)fail(message)};
const id=value=>typeof value==='string'&&/^[A-Za-z0-9_-]{1,128}$/.test(value);
const qty=value=>Number.isSafeInteger(value)&&value>0&&value<=10000;
const b64=bytes=>{let s='';for(let i=0;i<bytes.length;i+=16384)s+=String.fromCharCode(...bytes.subarray(i,i+16384));return btoa(s)};
const unb64=s=>{ok(typeof s==='string'&&s.length<=4096,'bad signature');return Uint8Array.from(atob(s),c=>c.charCodeAt(0))};
const utf=s=>new TextEncoder().encode(s);
const keyOf=(cycle,family)=>cycle+'|'+family;
const own=(x,k)=>Object.prototype.hasOwnProperty.call(x,k);
const get=(x,k)=>own(x,k)?x[k]:undefined;
const canonical=value=>{
  if(value===null||typeof value==='string'||typeof value==='boolean'||typeof value==='number'){
    if(typeof value==='number')ok(Number.isSafeInteger(value),'invalid number');
    return JSON.stringify(value);
  }
  if(Array.isArray(value))return '['+value.map(canonical).join(',')+']';
  ok(value&&typeof value==='object','invalid data');
  return '{'+Object.keys(value).sort().map(k=>JSON.stringify(k)+':'+canonical(value[k])).join(',')+'}';
};
function exactKeys(v,keys){ok(v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).sort().join('|')===keys.slice().sort().join('|'),'unexpected fields')}
function checkIds(...ids){ok(ids.every(id),'invalid identifier')}
function checkEnvelope(e,type){exactKeys(e,['payload','signature']);ok(e.payload?.v===VERSION&&e.payload?.type===type,'wrong protocol/type')}
const publicKey=async jwk=>crypto.subtle.importKey('jwk',jwk,{name:'ECDSA',namedCurve:'P-256'},false,['verify']);
const algorithm={name:'ECDSA',hash:'SHA-256'};
export async function fingerprint(jwk){
  const p=await publicKey(jwk);
  const raw=await crypto.subtle.exportKey('raw',await crypto.subtle.importKey('jwk',jwk,{name:'ECDSA',namedCurve:'P-256'},true,['verify']));
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',raw)),x=>x.toString(16).padStart(2,'0')).join('');
}
export async function generateTestIdentity(){
  // Test harness only. Production Android must use non-exportable Android Keystore keys.
  const keys=await crypto.subtle.generateKey({name:'ECDSA',namedCurve:'P-256'},true,['sign','verify']);
  return {privateKey:keys.privateKey,publicJwk:await crypto.subtle.exportKey('jwk',keys.publicKey)};
}
export async function signPayload(payload,privateKey){
  return {payload:clone(payload),signature:b64(new Uint8Array(await crypto.subtle.sign(algorithm,privateKey,utf(canonical(payload)))))};
}
export async function verifyEnvelope(envelope,jwk){
  try{const k=await publicKey(jwk);return await crypto.subtle.verify(algorithm,k,unb64(envelope.signature),utf(canonical(envelope.payload)))}catch{return false}
}
export function newMother({deployment,mother,epoch,motherKey}){
  checkIds(deployment,mother,epoch);ok(motherKey?.kty==='EC'&&motherKey.crv==='P-256','bad mother key');
  return {v:VERSION,deployment,mother,epoch,motherKey:clone(motherKey),devices:{[mother]:{key:clone(motherKey),role:'mother'}},grants:{},outcomes:{},messages:{},closed:{}};
}
// Pairing must compare the public-key fingerprint out-of-band and prove possession first.
// Pass only the key already checked by the native pairing layer.
export async function enrollVerified(state,{device,key,confirmedFingerprint}){
  checkIds(device);ok(device!==state.mother,'mother already registered');
  ok(await fingerprint(key)===confirmedFingerprint,'fingerprint mismatch');
  const prev=get(state.devices,device);
  if(prev){ok(canonical(prev.key)===canonical(key),'device identity collision');return clone(state)}
  const next=clone(state);
  Object.defineProperty(next.devices,device,{value:{key:clone(key),role:'distributor'},enumerable:true,writable:true,configurable:true});
  return next;
}
function context(state,p){ok(p.deployment===state.deployment&&p.mother===state.mother&&p.epoch===state.epoch,'wrong neighborhood/authority')}
export async function issueGrant(state,{grant,cycle,family,device,maxQty},privateKey){
  checkIds(grant,cycle,family,device);ok(qty(maxQty),'invalid quota');
  ok(get(state.devices,device),'unregistered device');ok(!get(state.closed,cycle),'cycle already closed');
  const key=keyOf(cycle,family);ok(!get(state.grants,key),'family already allocated in this cycle');
  ok(!Object.values(state.grants).some(e=>e.payload.grant===grant),'duplicate grant identifier');
  const payload={v:VERSION,type:'grant',deployment:state.deployment,mother:state.mother,epoch:state.epoch,grant,cycle,family,device,maxQty};
  const envelope=await signPayload(payload,privateKey);
  ok(await verifyEnvelope(envelope,state.motherKey),'wrong mother signer');
  const next=clone(state);next.grants[key]=envelope;
  return {state:next,packet:envelope};
}
export function newDistributor({deployment,mother,epoch,device,motherKey}){
  checkIds(deployment,mother,epoch,device);return {v:VERSION,deployment,mother,epoch,device,motherKey:clone(motherKey),grants:{},outbox:{},acked:{}};
}
export async function acceptGrant(state,envelope){
  checkEnvelope(envelope,'grant');
  const p=envelope.payload;exactKeys(p,['v','type','deployment','mother','epoch','grant','cycle','family','device','maxQty']);context(state,p);
  checkIds(p.grant,p.cycle,p.family,p.device);ok(p.device===state.device&&qty(p.maxQty),'grant not for this device');
  ok(await verifyEnvelope(envelope,state.motherKey),'mother signature invalid');
  const key=keyOf(p.cycle,p.family),prev=get(state.grants,key);
  if(prev){ok(canonical(prev.payload)===canonical(p),'contradictory grant');return clone(state)}
  ok(!Object.values(state.grants).some(e=>e.payload.grant===p.grant),'grant identity collision');
  const next=clone(state);next.grants[key]=clone(envelope);return next;
}
export async function recordOutcome(state,{cycle,family,receipt,kind,quantity},privateKey){
  checkIds(cycle,family,receipt);ok(kind==='delivered'||kind==='unused','invalid outcome');
  const key=keyOf(cycle,family),g=get(state.grants,key)?.payload;
  ok(g,'no allocation');ok(!get(state.outbox,key),'already resolved locally');
  ok(!Object.values(state.outbox).some(e=>e.payload.receipt===receipt),'duplicate receipt identifier');
  ok(kind==='unused'?quantity===0:qty(quantity)&&quantity<=g.maxQty,'quantity exceeds allocated quota');
  const p={v:VERSION,type:'outcome',deployment:state.deployment,mother:state.mother,epoch:state.epoch,device:state.device,grant:g.grant,cycle,family,receipt,kind,quantity};
  const packet=await signPayload(p,privateKey);
  const next=clone(state);next.outbox[key]=packet;return {state:next,packet};
}
export async function importOutcome(state,envelope,privateKey){
  checkEnvelope(envelope,'outcome');const p=envelope.payload;
  exactKeys(p,['v','type','deployment','mother','epoch','device','grant','cycle','family','receipt','kind','quantity']);context(state,p);
  checkIds(p.device,p.grant,p.cycle,p.family,p.receipt);
  const key=keyOf(p.cycle,p.family),g=get(state.grants,key)?.payload,d=get(state.devices,p.device);
  ok(g&&d&&g.grant===p.grant&&g.device===p.device,'not assigned to signer');
  ok(p.kind==='delivered'?qty(p.quantity)&&p.quantity<=g.maxQty:p.kind==='unused'&&p.quantity===0,'invalid quantity/outcome');
  ok(await verifyEnvelope(envelope,d.key),'distributor signature invalid');
  const previous=get(state.outcomes,key),seen=get(state.messages,p.receipt);
  if(previous)ok(canonical(previous.payload)===canonical(p),'conflicting outcome for allocation');
  if(seen)ok(seen===key,'receipt reused for another family');
  if(!previous)ok(!get(state.closed,p.cycle),'cycle already closed');
  const ack={v:VERSION,type:'ack',deployment:state.deployment,mother:state.mother,epoch:state.epoch,device:p.device,grant:p.grant,cycle:p.cycle,family:p.family,receipt:p.receipt,kind:p.kind,quantity:p.quantity};
  const packet=await signPayload(ack,privateKey);ok(await verifyEnvelope(packet,state.motherKey),'wrong mother signer');
  const next=clone(state);
  if(!previous){next.outcomes[key]=clone(envelope);Object.defineProperty(next.messages,p.receipt,{value:key,enumerable:true,writable:true,configurable:true})}
  return {state:next,packet,duplicate:!!previous};
}
export async function acceptAck(state,envelope){
  checkEnvelope(envelope,'ack');const p=envelope.payload;
  exactKeys(p,['v','type','deployment','mother','epoch','device','grant','cycle','family','receipt','kind','quantity']);context(state,p);
  checkIds(p.device,p.grant,p.cycle,p.family,p.receipt);
  const key=keyOf(p.cycle,p.family),local=get(state.outbox,key)?.payload;
  ok(local&&p.device===state.device&&canonical({...local,type:'ack'})===canonical(p),'ack not for local receipt');
  ok(await verifyEnvelope(envelope,state.motherKey),'ack signature invalid');
  const next=clone(state);next.acked[key]=clone(envelope);return next;
}
export function pendingOutcomes(state){return Object.entries(state.outbox).filter(([key])=>!get(state.acked,key)).map(([,packet])=>clone(packet))}
export function closeCycle(state,cycle){
  checkIds(cycle);ok(!Object.entries(state.grants).some(([key,g])=>g.payload.cycle===cycle&&!get(state.outcomes,key)),'unsettled offline allocations');
  const next=clone(state);Object.defineProperty(next.closed,cycle,{value:true,enumerable:true,writable:true,configurable:true});return next;
}
export function totals(state,cycle){
  const rows=Object.values(state.outcomes).map(e=>e.payload).filter(p=>p.cycle===cycle&&p.kind==='delivered');
  return {families:rows.length,cylinders:rows.reduce((sum,p)=>sum+p.quantity,0)};
}
