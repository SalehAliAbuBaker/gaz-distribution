import test from 'node:test';
import assert from 'node:assert/strict';
import * as P from './authority.mjs';
import {webcrypto} from 'node:crypto';
if(!globalThis.crypto)globalThis.crypto=webcrypto;

async function setup(){
 const [m,a,b]=await Promise.all([P.generateTestIdentity(),P.generateTestIdentity(),P.generateTestIdentity()]);
 let mother=P.newMother({deployment:'rawda',mother:'main',epoch:'epoch1',motherKey:m.publicJwk});
 mother=await P.enrollVerified(mother,{device:'A',key:a.publicJwk,confirmedFingerprint:await P.fingerprint(a.publicJwk)});
 mother=await P.enrollVerified(mother,{device:'B',key:b.publicJwk,confirmedFingerprint:await P.fingerprint(b.publicJwk)});
 const mk=device=>P.newDistributor({deployment:'rawda',mother:'main',epoch:'epoch1',device,motherKey:m.publicJwk});
 return {m,a,b,mother,A:mk('A'),B:mk('B')};
}
test('exclusive allocation, offline delivery, replay and acknowledgement',async()=>{
 let {m,a,mother,A,B}=await setup();
 const issued=await P.issueGrant(mother,{grant:'g1',cycle:'c1',family:'f1',device:'A',maxQty:2},m.privateKey);mother=issued.state;
 await assert.rejects(P.issueGrant(mother,{grant:'g2',cycle:'c1',family:'f1',device:'B',maxQty:2},m.privateKey),/already allocated/);
 await assert.rejects(P.acceptGrant(B,issued.packet),/not for this device/);
 A=await P.acceptGrant(A,issued.packet);A=await P.acceptGrant(A,issued.packet);
 await assert.rejects(P.recordOutcome(A,{cycle:'c1',family:'f1',receipt:'r0',kind:'delivered',quantity:3},a.privateKey),/quota/);
 const out=await P.recordOutcome(A,{cycle:'c1',family:'f1',receipt:'r1',kind:'delivered',quantity:2},a.privateKey);A=JSON.parse(JSON.stringify(out.state));
 assert.equal(P.pendingOutcomes(A).length,1);
 await assert.rejects(P.recordOutcome(A,{cycle:'c1',family:'f1',receipt:'r2',kind:'delivered',quantity:1},a.privateKey),/already resolved/);
 assert.throws(()=>P.closeCycle(mother,'c1'),/unsettled/);
 const merged=await P.importOutcome(mother,out.packet,m.privateKey);mother=merged.state;
 const retry=await P.importOutcome(mother,out.packet,m.privateKey);assert.equal(retry.duplicate,true);
 assert.deepEqual(P.totals(retry.state,'c1'),{families:1,cylinders:2});
 A=await P.acceptAck(A,retry.packet);assert.equal(P.pendingOutcomes(A).length,0);
 mother=P.closeCycle(mother,'c1');assert.equal(mother.closed.c1,true);
 const late=await P.importOutcome(mother,out.packet,m.privateKey);assert.equal(late.duplicate,true);
});
test('tampering, foreign neighborhood, wrong signer and conflicting receipt rejected',async()=>{
 let {m,a,b,mother,A}=await setup();
 const {state,packet}=await P.issueGrant(mother,{grant:'g1',cycle:'c1',family:'f1',device:'A',maxQty:2},m.privateKey);mother=state;
 const tampered=structuredClone(packet);tampered.payload.maxQty=9;
 await assert.rejects(P.acceptGrant(A,tampered),/signature invalid/);
 await assert.rejects(P.acceptGrant({...A,deployment:'other'},packet),/wrong neighborhood/);
 await assert.rejects(P.acceptGrant({...A,epoch:'epoch2'},packet),/wrong neighborhood/);
 A=await P.acceptGrant(A,packet);
 const signedWrong=await P.recordOutcome(A,{cycle:'c1',family:'f1',receipt:'r1',kind:'delivered',quantity:1},b.privateKey);
 await assert.rejects(P.importOutcome(mother,signedWrong.packet,m.privateKey),/signature invalid/);
 const first=await P.recordOutcome(A,{cycle:'c1',family:'f1',receipt:'r1',kind:'delivered',quantity:1},a.privateKey);
 mother=(await P.importOutcome(mother,first.packet,m.privateKey)).state;
 const conflict=await P.signPayload({...first.packet.payload,receipt:'r2'},a.privateKey);
 await assert.rejects(P.importOutcome(mother,conflict,m.privateKey),/conflicting outcome/);
});
test('unused grant settles cycle but cannot be reallocated within that cycle',async()=>{
 let {m,a,mother,A}=await setup();
 let r=await P.issueGrant(mother,{grant:'g1',cycle:'c1',family:'f1',device:'A',maxQty:1},m.privateKey);mother=r.state;A=await P.acceptGrant(A,r.packet);
 let out=await P.recordOutcome(A,{cycle:'c1',family:'f1',receipt:'unused1',kind:'unused',quantity:0},a.privateKey);
 mother=(await P.importOutcome(mother,out.packet,m.privateKey)).state;
 assert.deepEqual(P.totals(mother,'c1'),{families:0,cylinders:0});
 await assert.rejects(P.issueGrant(mother,{grant:'g2',cycle:'c1',family:'f1',device:'B',maxQty:1},m.privateKey),/already allocated/);
 assert.equal(P.closeCycle(mother,'c1').closed.c1,true);
});
test('enrollment rejects unconfirmed fingerprint and replacement key',async()=>{
 const {mother,a,b}=await setup();
 await assert.rejects(P.enrollVerified(mother,{device:'C',key:a.publicJwk,confirmedFingerprint:'not-confirmed'}),/fingerprint mismatch/);
 await assert.rejects(P.enrollVerified(mother,{device:'A',key:b.publicJwk,confirmedFingerprint:await P.fingerprint(b.publicJwk)}),/identity collision/);
});
test('receipt ID cannot be reused for another allocated family',async()=>{
 let {m,a,mother,A}=await setup();
 for(const family of ['f1','f2']){
  const r=await P.issueGrant(mother,{grant:'g'+family,cycle:'c1',family,device:'A',maxQty:1},m.privateKey);
  mother=r.state;A=await P.acceptGrant(A,r.packet);
 }
 const one=await P.recordOutcome(A,{cycle:'c1',family:'f1',receipt:'r1',kind:'delivered',quantity:1},a.privateKey);
 mother=(await P.importOutcome(mother,one.packet,m.privateKey)).state;
 // Simulate duplicated or compromised offline state; mother must reject replay across families.
 const two=await P.recordOutcome(A,{cycle:'c1',family:'f2',receipt:'r1',kind:'delivered',quantity:1},a.privateKey);
 await assert.rejects(P.importOutcome(mother,two.packet,m.privateKey),/receipt reused/);
});
