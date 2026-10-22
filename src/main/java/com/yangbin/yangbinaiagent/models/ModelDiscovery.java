package com.yangbin.yangbinaiagent.models;

import com.fasterxml.jackson.databind.*;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Model listing only: no inference calls, key-bearing redirects, retries, or unbounded pagination. */
public final class ModelDiscovery {
    private ModelDiscovery(){}
    public record Discovered(String id,String name,List<String> reasoningEfforts){}
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"model-discovery-deadline");t.setDaemon(true);return t;});
    private static final int MAX_BYTES=2*1024*1024, MAX_MODELS=1000, MAX_PAGES=10;
    public static List<Discovered> fetch(ObjectMapper json,String provider,String baseUrl,String key,boolean allowPrivate)throws IOException {
        String base=ModelEndpointPolicy.normalize(baseUrl,allowPrivate);
        boolean anthropic="anthropic".equals(provider);
        String endpoint=base+(anthropic&&!base.endsWith("/v1")?"/v1/models":"/models");
        var resolver=new DnsResolver(){public InetAddress[] resolve(String host)throws UnknownHostException{return ModelEndpointPolicy.resolve(host,allowPrivate);}public String resolveCanonicalHostname(String host)throws UnknownHostException{ModelEndpointPolicy.resolve(host,allowPrivate);return host;}};
        var manager=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver).setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5)).setSocketTimeout(Timeout.ofSeconds(10)).build()).build();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        var result=new LinkedHashMap<String,Discovered>();var cursors=new HashSet<String>();String cursor=null;
        try(var http=HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().build()){
            for(int page=0;page<MAX_PAGES;page++){
                long remaining=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(remaining<=0)throw new IOException("模型列表获取超时，请稍后重试");
                String uri=endpoint+(anthropic?"?limit=100"+(cursor==null?"":"&after_id="+URLEncoder.encode(cursor,StandardCharsets.UTF_8)):cursor==null?"":"?after="+URLEncoder.encode(cursor,StandardCharsets.UTF_8));
                var request=new HttpGet(uri);request.setConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofMilliseconds(Math.min(10000,remaining))).setConnectionRequestTimeout(Timeout.ofSeconds(5)).build());
                request.setHeader("Accept","application/json");
                if(anthropic){request.setHeader("x-api-key",key);request.setHeader("anthropic-version","2023-06-01");}else request.setHeader("Authorization","Bearer "+key);
                var abort=DEADLINES.schedule(()->{request.cancel();},remaining,java.util.concurrent.TimeUnit.MILLISECONDS);
                JsonNode data;try{data=http.execute(request,response->{
                    if(response.getCode()<200||response.getCode()>=300)throw new IOException("服务未返回模型列表，请检查地址、权限或手动添加模型 ID");
                    if(response.getEntity()==null)throw new IOException("模型列表响应为空");
                    try(InputStream input=response.getEntity().getContent();var bytes=new ByteArrayOutputStream()){
                        byte[] buffer=new byte[8192];int size;while((size=input.read(buffer))!=-1){if(System.nanoTime()>deadline||bytes.size()+size>MAX_BYTES)throw new IOException("模型列表超过响应限制");bytes.write(buffer,0,size);}
                        return json.readTree(bytes.toByteArray());
                    }
                });}finally{abort.cancel(false);}
                if(data==null||!data.path("data").isArray())throw new IOException("服务返回的模型列表格式不支持，请手动添加模型 ID");
                for(JsonNode model:data.path("data")){
                    String id=model.path("id").asText("");if(!safe(id,200)||id.contains(key))throw new IOException("模型列表包含无效 ID");
                    String name=model.path("display_name").asText(model.path("name").asText(id));if(!safe(name,80)||name.contains(key))name=id;
                    result.put(id,new Discovered(id,name,ModelReasoning.supported(provider,id,model)));if(result.size()>MAX_MODELS)throw new IOException("模型列表数量超过上限，请手动添加需要的模型 ID");
                }
                if(!data.path("has_more").asBoolean(false))return List.copyOf(result.values());
                cursor=data.path("last_id").asText("");if(cursor.isBlank()&&data.path("data").size()>0)cursor=data.path("data").get(data.path("data").size()-1).path("id").asText("");
                if(!safe(cursor,200)||cursor.contains(key)||!cursors.add(cursor))throw new IOException("模型列表分页无效");
            }
            throw new IOException("模型列表分页超过上限，请手动添加需要的模型 ID");
        }catch(Exception error){
            // Never return supplier error bodies, headers, URLs or credentials to API clients/loggers.
            throw new IOException("无法获取完整模型列表，请检查连接、权限或手动添加模型 ID");
        }
    }
    private static boolean safe(String text,int max){return text!=null&&!text.isBlank()&&text.length()<=max&&text.chars().noneMatch(c->c<32||c==127);}
}
