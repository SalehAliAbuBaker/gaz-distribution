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
    private static SSLContext serverContext(PrivateKey key,X509Certificate cert)throws Exception{
        X509KeyManager manager=new X509KeyManager(){
            public String[] getClientAliases(String t,Principal[] i){return null;}
            public String chooseClientAlias(String[] t,Principal[] i,Socket s){return null;}
            public String[] getServerAliases(String t,Principal[] i){return t!=null&&t.toUpperCase(Locale.ROOT).startsWith("EC")?new String[]{"gaz"}:null;}
            public String chooseServerAlias(String t,Principal[] i,Socket s){return getServerAliases(t,i)!=null?"gaz":null;}
            public X509Certificate[] getCertificateChain(String alias){return "gaz".equals(alias)?new X509Certificate[]{cert}:null;}
            public PrivateKey getPrivateKey(String alias){return "gaz".equals(alias)?key:null;}
        };
        SSLContext ctx=SSLContext.getInstance("TLS");ctx.init(new KeyManager[]{manager},null,new SecureRandom());return ctx;
    }
    private static SSLContext clientContext(String pinned)throws Exception{
        X509TrustManager tm=new X509TrustManager(){
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] chain,String type)throws CertificateException{throw new CertificateException("No client certificate authentication");}
            public void checkServerTrusted(X509Certificate[] chain,String type)throws CertificateException{
                if(chain==null||chain.length<1||!MessageDigest.isEqual(chain[0].getPublicKey().getEncoded(),PairPackets.bytes(pinned)))throw new CertificateException("Mother key mismatch");
            }
        };
        SSLContext ctx=SSLContext.getInstance("TLS");ctx.init(null,new TrustManager[]{tm},new SecureRandom());return ctx;
    }
    GazLan(String ip,PrivateKey key,X509Certificate cert)throws Exception{
        listener=(SSLServerSocket)serverContext(key,cert).getServerSocketFactory().createServerSocket();listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(localAddress(ip),PORT));listener.setEnabledProtocols(new String[]{"TLSv1.2"});listener.setSoTimeout(1500);
    }
    void serve(Handler handler,Status status){
        while(!closed){try{
            SSLSocket socket=(SSLSocket)listener.accept();current=socket;
            try(SSLSocket s=socket){s.setSoTimeout(12000);s.startHandshake();String challenge=UUID.randomUUID().toString();
                DataInputStream in=new DataInputStream(s.getInputStream());DataOutputStream out=new DataOutputStream(s.getOutputStream());
                SyncWire.send(out,challenge);String response=handler.respond(SyncWire.receive(in),challenge);SyncWire.send(out,response);status.update("تمت مزامنة جهاز مرتبط");
            }finally{current=null;}
        }catch(SocketTimeoutException ignored){}catch(Exception e){if(!closed)status.update("اتصال لم يكتمل أو جهاز غير مصرح به");}}
    }
    interface Exchange{String request(String challenge)throws Exception;void accept(String response,String challenge)throws Exception;}
    static void connect(String ip,String pinned,Exchange exchange)throws Exception{
        try(SSLSocket socket=(SSLSocket)clientContext(pinned).getSocketFactory().createSocket()){
            socket.connect(new InetSocketAddress(localAddress(ip),PORT),5000);socket.setEnabledProtocols(new String[]{"TLSv1.2"});socket.setSoTimeout(15000);socket.startHandshake();
            DataInputStream in=new DataInputStream(socket.getInputStream());DataOutputStream out=new DataOutputStream(socket.getOutputStream());
            String challenge=SyncWire.receive(in);PairPackets.uuid(challenge);SyncWire.send(out,exchange.request(challenge));exchange.accept(SyncWire.receive(in),challenge);
        }
    }
    public void close(){closed=true;try{listener.close();}catch(Exception ignored){}SSLSocket s=current;if(s!=null)try{s.close();}catch(Exception ignored){}}
}
