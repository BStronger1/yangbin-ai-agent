package com.yangbin.yangbinaiagent.models;

import java.net.*;
import java.util.Locale;

/** Validate at configuration time AND in the HTTP transport's DNS resolver. */
public final class ModelEndpointPolicy {
    private ModelEndpointPolicy() {}
    public static String normalize(String raw, boolean allowPrivate) {
        if(raw==null||raw.isBlank()||raw.length()>2048)throw new IllegalArgumentException("请输入有效的模型 Base URL");
        try {
            URI uri=URI.create(raw.strip());
            if(!("http".equals(uri.getScheme())||"https".equals(uri.getScheme()))||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getPort()==0||uri.getPort() < -1||uri.getPort()>65535)
                throw new IllegalArgumentException("Base URL 仅允许无账号、查询参数和片段的 HTTP(S) 地址");
            if(!allowPrivate&&!"https".equals(uri.getScheme()))throw new IllegalArgumentException("个人模型需使用HTTPS");
            String path=uri.getRawPath();if(path!=null&&(path.contains("\\")||path.contains("..")||path.toLowerCase(Locale.ROOT).contains("%2e")||path.toLowerCase(Locale.ROOT).contains("%2f")))throw new IllegalArgumentException("Base URL 路径无效");
            resolve(uri.getHost(),allowPrivate);
            return uri.toASCIIString().replaceAll("/+$","");
        }catch(UnknownHostException e){throw new IllegalArgumentException("模型地址无法解析或不允许访问该网络地址");}
        catch(IllegalArgumentException e){throw new IllegalArgumentException("Base URL 无效或指向不允许的网络地址");}
    }
    public static InetAddress[] resolve(String host,boolean allowPrivate)throws UnknownHostException {
        InetAddress[] addresses=InetAddress.getAllByName(host);
        if(addresses.length==0)throw new UnknownHostException("Model host unavailable");
        if(!allowPrivate)for(InetAddress address:addresses)if(!publicAddress(address))throw new UnknownHostException("Private model host is not allowed");
        return addresses;
    }
    public static boolean publicAddress(InetAddress address) {
        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return false;
        byte[] b=address.getAddress();int first=b[0]&255;
        if(b.length==4){int second=b[1]&255;return first!=0&&first!=127&&first<224&&!(first==100&&second>=64&&second<=127)&&!(first==198&&(second==18||second==19));}
                // Only global unicast IPv6; exclude transition/tunnelling and documentation ranges.
        int second=b[1]&255,third=b[2]&255,fourth=b[3]&255;
        return (first&0xe0)==0x20
            && !(first==0x20&&second==0x01&&(third&0xfe)==0)
            && !(first==0x20&&second==0x01&&third==0x0d&&fourth==0xb8)
            && !(first==0x20&&second==0x02)
            && !(first==0x3f&&second==0xff&&(third&0xf0)==0);
    }
}
