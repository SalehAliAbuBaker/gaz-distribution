package ye.gaz.distribution;

import javax.net.ssl.*;
import java.net.*;
import java.io.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateException;
import java.util.*;
import org.json.JSONObject;

/** Foreground-only LAN transport. Pin the already-paired mother's EC public key. */
final class GazLan implements AutoCloseable {
    static final int PORT=43127;
    interface Handler{String respond(String request,String challenge)throws Exception;}
    interface Status{void update(String message);}
    private SSLServerSocket listener;
    private volatile SSLSocket current;
    private volatile boolean closed;
    static ArrayList<String> addresses()throws Exception{
        ArrayList<String> out=new ArrayList<>();Enumeration<NetworkInterface> nets=NetworkInterface.getNetworkInterfaces();
        while(nets!=null&&nets.hasMoreElements()){NetworkInterface n=nets.nextElement();if(!n.isUp()||n.isLoopback())continue;
            for(Enumeration<InetAddress> a=n.getInetAddresses();a.hasMoreElements();){InetAddress ip=a.nextElement();if(ip instanceof Inet4Address&&ip.isSiteLocalAddress()&&!out.contains(ip.getHostAddress()))out.add(ip.getHostAddress());}}
        return out;
    }
    static InetAddress localAddress(String ip)throws Exception{
        PairPackets.require(ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"),"Invalid IPv4");
        InetAddress a=InetAddress.getByName(ip);PairPackets.require(a instanceof Inet4Address&&a.isSiteLocalAddress()&&!a.isLoopbackAddress(),"Local Wi-Fi address required");return a;
    }
    static SSLContext serverContext(PrivateKey identity)throws Exception{
        LanCertificate.Credentials credentials=LanCertificate.create(identity);PrivateKey key=credentials.key;X509Certificate cert=credentials.cert;
        X509ExtendedKeyManager manager=new X509ExtendedKeyManager(){
            public String[] getClientAliases(String t,Principal[] i){return null;}
            public String chooseClientAlias(String[] t,Principal[] i,Socket s){return null;}
            public String[] getServerAliases(String t,Principal[] i){return t!=null&&t.toUpperCase(Locale.ROOT).startsWith("EC")?new String[]{"gaz"}:null;}
            public String chooseServerAlias(String t,Principal[] i,Socket s){return getServerAliases(t,i)!=null?"gaz":null;}
            public X509Certificate[] getCertificateChain(String alias){return "gaz".equals(alias)?new X509Certificate[]{cert}:null;}
            public PrivateKey getPrivateKey(String alias){return "gaz".equals(alias)?key:null;}
            public String chooseEngineServerAlias(String t,Principal[] i,SSLEngine e){return getServerAliases(t,i)!=null?"gaz":null;}
        };
        SSLContext ctx=SSLContext.getInstance("TLS");ctx.init(new KeyManager[]{manager},null,new SecureRandom());return ctx;
    }
    static SSLContext clientContext(String pinned)throws Exception{
        PublicKey authority=PairPackets.key(pinned);
        X509TrustManager tm=new X509TrustManager(){
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] chain,String type)throws CertificateException{throw new CertificateException("No client certificate authentication");}
            public void checkServerTrusted(X509Certificate[] chain,String type)throws CertificateException{
                try{if(chain==null||chain.length<1)throw new CertificateException("Missing certificate");LanCertificate.verify(chain[0],authority);}catch(GeneralSecurityException e){throw new CertificateException("Mother identity mismatch",e);}
            }
        };
        SSLContext ctx=SSLContext.getInstance("TLS");ctx.init(null,new TrustManager[]{tm},new SecureRandom());return ctx;
    }
    GazLan(String ip,PrivateKey key)throws Exception{
        listener=(SSLServerSocket)serverContext(key).getServerSocketFactory().createServerSocket();listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(localAddress(ip),PORT));listener.setEnabledProtocols(new String[]{"TLSv1.2"});listener.setSoTimeout(1500);
    }
    void serve(Handler handler,Status status){
        while(!closed){String stage="LAN-03";try{
            SSLSocket socket=(SSLSocket)listener.accept();current=socket;stage="LAN-04";if(closed){socket.close();break;}
            try(SSLSocket s=socket){s.setSoTimeout(12000);s.startHandshake();stage="LAN-05";String challenge=UUID.randomUUID().toString();
                DataInputStream in=new DataInputStream(s.getInputStream());DataOutputStream out=new DataOutputStream(s.getOutputStream());
                SyncWire.send(out,challenge);String request=SyncWire.receive(in);stage="LAN-06";String response=handler.respond(request,challenge);stage="LAN-07";SyncWire.send(out,response);status.update("تمت مزامنة جهاز مرتبط");
            }finally{current=null;}
        }catch(SocketTimeoutException e){if(!closed&&!"LAN-03".equals(stage))status.update(diagnostic(new Failure(stage,e)));}catch(Exception e){if(!closed)status.update(diagnostic(new Failure(stage,e)));}}
    }
    interface SocketFactory{Socket create(InetAddress target)throws Exception;}
    static final class Failure extends IOException{final String stage;Failure(String s,Throwable e){super(s,e);stage=s;}}
    static String diagnostic(Throwable e){String stage=e instanceof Failure?((Failure)e).stage:"LAN";Throwable c=e instanceof Failure&&e.getCause()!=null?e.getCause():e;String reason=c instanceof SocketTimeoutException?"انتهت المهلة":c instanceof ConnectException?"تعذر الوصول إلى المنفذ":c instanceof NoRouteToHostException?"لا يوجد مسار عبر Wi-Fi":c instanceof SSLException?"فشل التحقق من الاتصال المشفر":c instanceof GeneralSecurityException?"تعذر التحقق من الهوية":c instanceof EOFException?"أغلق الطرف الآخر الاتصال":"تعذر إتمام العملية";return stage+" — "+reason+" ("+c.getClass().getSimpleName()+")";}
    interface Exchange{String request(String challenge)throws Exception;void accept(String response,String challenge)throws Exception;}
    static void connect(String ip,String pinned,SocketFactory factory,Exchange exchange)throws Exception{
        String stage="LAN-11";
        try{InetAddress target=localAddress(ip);try(Socket raw=factory.create(target)){
            stage="LAN-12";raw.connect(new InetSocketAddress(target,PORT),8000);raw.setSoTimeout(20000);
            stage="LAN-13";try(SSLSocket socket=(SSLSocket)clientContext(pinned).getSocketFactory().createSocket(raw,ip,PORT,true)){
                socket.setEnabledProtocols(new String[]{"TLSv1.2"});socket.setSoTimeout(20000);socket.startHandshake();stage="LAN-14";
                DataInputStream in=new DataInputStream(socket.getInputStream());DataOutputStream out=new DataOutputStream(socket.getOutputStream());
                String challenge=SyncWire.receive(in);PairPackets.uuid(challenge);stage="LAN-15";SyncWire.send(out,exchange.request(challenge));stage="LAN-16";
                String result=SyncWire.receive(in);stage="LAN-17";exchange.accept(result,challenge);
            }
        }}catch(Exception e){throw new Failure(stage,e);}
    }
    public void close(){closed=true;try{listener.close();}catch(Exception ignored){}SSLSocket s=current;if(s!=null)try{s.close();}catch(Exception ignored){}}
}
