package ye.gaz.distribution;
import android.content.Context;
import android.net.*;
import java.net.*;
import java.util.*;
final class GazNetwork {
    private static void add(Map<String,String> out,InetAddress ip,String label){if(ip instanceof Inet4Address&&ip.isSiteLocalAddress()&&!ip.isLoopbackAddress())out.putIfAbsent(ip.getHostAddress(),label+" — "+ip.getHostAddress());}
    static LinkedHashMap<String,String> addresses(Context context)throws Exception{
        LinkedHashMap<String,String> out=new LinkedHashMap<>();ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        for(Network net:cm.getAllNetworks()){NetworkCapabilities c=cm.getNetworkCapabilities(net);LinkProperties lp=cm.getLinkProperties(net);
            if(c==null||lp==null||!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||c.hasTransport(NetworkCapabilities.TRANSPORT_VPN))continue;
            for(LinkAddress a:lp.getLinkAddresses())add(out,a.getAddress(),"Wi-Fi");}
        Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();
        while(all!=null&&all.hasMoreElements()){NetworkInterface n=all.nextElement();if(!n.isUp()||n.isLoopback())continue;String name=n.getName().toLowerCase(Locale.ROOT);
            if(!(name.startsWith("wlan")||name.startsWith("wifi")||name.startsWith("ap")||name.startsWith("swlan")||name.startsWith("softap")||name.startsWith("eth")))continue;
            for(Enumeration<InetAddress> a=n.getInetAddresses();a.hasMoreElements();)add(out,a.nextElement(),"Wi-Fi / نقطة الاتصال");}
        return out;
    }
    static Socket socket(Context context,InetAddress target)throws Exception{
        ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);Network candidate=null;
        for(Network net:cm.getAllNetworks()){NetworkCapabilities c=cm.getNetworkCapabilities(net);LinkProperties lp=cm.getLinkProperties(net);
            if(c==null||lp==null||!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||c.hasTransport(NetworkCapabilities.TRANSPORT_VPN))continue;
            if(candidate==null)candidate=net;
            for(RouteInfo route:lp.getRoutes())if(route.getDestination().contains(target)&&route.getDestination().getPrefixLength()>0)return net.getSocketFactory().createSocket();}
        if(candidate==null)throw new NoRouteToHostException("Wi-Fi required");return candidate.getSocketFactory().createSocket();
    }
}
