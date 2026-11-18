package ye.gaz.distribution;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.util.UUID;

/** Durable, device-bound identity. Pairing is NOT permission to deliver gas. */
final class GazPairing {
    private static final String ALIAS="gaz-device-identity-p256-v1";
    private final GazStore store;
    private final String device;
    private final PrivateKey secret;
    private final String pub;
    private final java.security.cert.X509Certificate cert;
    GazPairing(GazStore store,String device)throws Exception{
        this.store=store;this.device=device;PairPackets.uuid(device);
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        JSONObject saved=store.pairRead();
        if(!ks.containsAlias(ALIAS)){
            if(!"independent".equals(saved.optString("role","independent")))throw new GeneralSecurityException("Pair identity missing");
            KeyPairGenerator gen=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore");
            gen.initialize(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN|KeyProperties.PURPOSE_VERIFY).setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build());
            gen.generateKeyPair();ks.load(null);
        }
        secret=(PrivateKey)ks.getKey(ALIAS,null);cert=(java.security.cert.X509Certificate)ks.getCertificate(ALIAS);pub=PairPackets.b64(cert.getPublicKey().getEncoded());
        if(!"independent".equals(saved.optString("role","independent"))){
            PairPackets.require(device.equals(saved.getString("device"))&&pub.equals(saved.getString("pub")),"Identity changed");
        }
    }
    void allocate(String child,String cycle,java.util.List<String> families)throws Exception{
        synchronized(store){
            JSONObject state=status(),local=new JSONObject(store.readState());
            for(String family:families)SyncRules.grant(state,local,child,cycle,family,secret);
            store.commitSync(state,null);
        }
    }
    JSONArray allocations()throws Exception{
        synchronized(store){
            JSONObject s=status(),g=SyncRules.map(s,"grants"),out=SyncRules.map(s,"outbox"),acked=SyncRules.map(s,"acked"),receipts=SyncRules.map(s,"receipts");JSONArray rows=new JSONArray();
            boolean mother="mother".equals(s.getString("role"));JSONObject local=mother?new JSONObject(store.readState()):null;JSONObject labels=SyncRules.map(s,"squareLabels");
            for(java.util.Iterator<String> it=g.keys();it.hasNext();){
                String k=it.next();JSONObject p=SyncWire.peek(g.getString(k));
                JSONObject row=new JSONObject(p.toString()).put("key",k).put("done",mother?receipts.has(k):out.has(k)).put("synced",mother?receipts.has(k):acked.optBoolean(k));
                if(mother){row.put("distributorName",SyncRules.child(s,p.getString("device")).getString("name"));JSONObject f=SyncRules.find(local.getJSONArray("families"),p.getString("family"));row.put("square",f==null?"":f.optString("square",""));}else row.put("square",labels.optString(k,p.optString("square","")));
                JSONObject moves=mother?receipts:out;if(moves.has(k)){JSONObject receipt=SyncWire.peek(moves.getString(k));row.put("q",receipt.getInt("q")).put("at",receipt.getString("at")).put("operator",receipt.optString("operator",""));}
                rows.put(row);
            }return rows;
        }
    }
    void record(String key,int quantity,String operatorId,String operator)throws Exception{
        synchronized(store){JSONObject state=status();SyncRules.record(state,key,quantity,secret,operatorId,operator);store.commitSync(state,null);}
    }
    GazLan listen(String ip)throws Exception{
        PairPackets.require("mother".equals(status().getString("role")),"Mother required");return new GazLan(ip,secret);
    }
    String respond(String request,String challenge)throws Exception{
        synchronized(store){
            JSONObject state=status(),local=new JSONObject(store.readState());PairPackets.require("mother".equals(state.getString("role")),"Mother required");
            JSONObject p=SyncRules.respond(state,local,request,challenge);String response=SyncWire.sign(p,secret);
            store.commitSync(state,local);return response;
        }
    }
    String syncQr(String ip)throws Exception{return SyncEndpoint.sign(status(),ip,secret);}
    String readSyncQr(String packet)throws Exception{return SyncEndpoint.read(status(),packet);}
    void synchronize(android.content.Context context,String ip)throws Exception{
        JSONObject before=status();PairPackets.require("distributor".equals(before.getString("role")),"Distributor required");
        GazLan.connect(ip,before.getString("motherPub"),target->GazNetwork.socket(context,target),new GazLan.Exchange(){
            public String request(String challenge)throws Exception{synchronized(store){return SyncWire.sign(SyncRules.request(status(),challenge,secret),secret);}}
            public void accept(String response,String challenge)throws Exception{synchronized(store){JSONObject state=status();SyncRules.acceptResponse(state,response,challenge);state.put("lastSync",System.currentTimeMillis());store.commitSync(state,null);}}
        });
    }
    String fingerprint()throws Exception{return PairPackets.shortFingerprint(pub);}
    JSONObject status()throws Exception{return store.pairRead();}
    private JSONObject fresh(String role)throws Exception{return new JSONObject().put("role",role).put("device",device).put("pub",pub);}
    synchronized void becomeMother()throws Exception{
        PairPackets.require("independent".equals(status().optString("role","independent")),"Already assigned");
        JSONObject local=new JSONObject(store.readState());String dep=local.getString("deploymentId"),area=local.getString("area");
        PairPackets.uuid(dep);PairPackets.require(!area.trim().isEmpty()&&area.length()<=100,"Invalid area");
        JSONObject next=fresh("mother").put("deployment",dep).put("area",area).put("epoch",UUID.randomUUID().toString()).put("devices",new JSONObject());
        store.pairWrite(next);
    }
    synchronized String request(String name)throws Exception{
        JSONObject state=status();String role=state.optString("role","independent");
        if("pending".equals(role))return state.getString("request");
        PairPackets.require("independent".equals(role),"Already assigned");
        JSONObject local=new JSONObject(store.readState());
        for(String k:new String[]{"families","cycles","deliveries"})PairPackets.require(local.getJSONArray(k).length()==0,"Distributor must be empty");
        name=name.trim();PairPackets.require(!name.isEmpty()&&name.length()<=60,"Invalid name");
        String packet=PairPackets.pack(new JSONObject().put("v",1).put("type","request").put("device",device).put("nonce",UUID.randomUUID().toString()).put("name",name).put("pub",pub),secret);
        PairPackets.unpack(packet,"request");
        store.pairWrite(fresh("pending").put("request",packet));return packet;
    }
    synchronized void cancelPending()throws Exception{
        PairPackets.require("pending".equals(status().optString("role")),"Not pending");
        // No grants can exist in this release. The mother may retain an orphan registration.
        store.pairWrite(fresh("independent"));
    }
    synchronized String approve(String request)throws Exception{
        JSONObject state=status();PairPackets.require("mother".equals(state.optString("role")),"Mother required");
        JSONObject r=PairPackets.unpack(request,"request");String child=r.getString("device"),childPub=r.getString("pub");
        PairPackets.require(!device.equals(child)&&!pub.equals(childPub),"Cannot enroll self");
        JSONObject devices=state.getJSONObject("devices");String hash=PairPackets.digest(PairPackets.utf(request));
        if(devices.has(child)){
            JSONObject old=devices.getJSONObject(child);
            PairPackets.require(hash.equals(old.getString("request"))&&childPub.equals(old.getString("pub")),"Conflicting registration");
            return old.getString("approval");
        }
        PairPackets.require(devices.length()<50,"Device limit");
        for(java.util.Iterator<String> it=devices.keys();it.hasNext();)PairPackets.require(!childPub.equals(devices.getJSONObject(it.next()).getString("pub")),"Duplicate public key");
        JSONObject p=new JSONObject().put("v",1).put("type","approval").put("device",child).put("request",hash).put("mother",device).put("deployment",state.getString("deployment")).put("epoch",state.getString("epoch")).put("area",state.getString("area")).put("pub",pub);
        String packet=PairPackets.pack(p,secret);PairPackets.unpack(packet,"approval");
        devices.put(child,new JSONObject().put("name",r.getString("name")).put("pub",childPub).put("request",hash).put("approval",packet).put("registered",System.currentTimeMillis()));
        store.pairWrite(state);return packet;
    }
    synchronized JSONObject inspectApproval(String packet)throws Exception{
        JSONObject state=status();PairPackets.require("pending".equals(state.optString("role")),"No pending request");
        return PairPackets.matchResponse(packet,state.getString("request"),device,pub);
    }
    synchronized void accept(String packet)throws Exception{
        JSONObject p=inspectApproval(packet);
        JSONObject next=fresh("distributor").put("mother",p.getString("mother")).put("motherPub",p.getString("pub")).put("deployment",p.getString("deployment")).put("epoch",p.getString("epoch")).put("area",p.getString("area")).put("approval",packet);
        store.pairWrite(next);
    }
}
