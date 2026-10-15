package com.yangbin.yangbinaiagent.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Server-side Amap client. Never return provider URLs, credentials or raw error bodies. */
final class AmapPlaces {
    record Result(PlanningEvidence.Location location,List<PlanningEvidence.Place> places) {}
    static final class Unavailable extends Exception { Unavailable(String message){super(message);} }
    private final ObjectMapper json;
    private final String key,base;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    AmapPlaces(ObjectMapper json,String key,String base){this.json=json;this.key=key==null?"":key.strip();this.base=base;}
    boolean configured(){return !key.isEmpty();}
    private static String enc(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private JsonNode request(String path,String query)throws Unavailable {
        try {
            var request=HttpRequest.newBuilder(URI.create(base+path+"?"+query+"&key="+enc(key)))
                    .timeout(Duration.ofSeconds(12)).GET().build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var body=response.body()) {
                if(response.statusCode()!=200)throw new Unavailable("服务请求失败");
                byte[] bytes=body.readNBytes(2_000_001);
                if(bytes.length>2_000_000)throw new Unavailable("响应过大");
                var node=json.readTree(bytes);
                if(!"1".equals(node.path("status").asText())) {
                    String message=switch(node.path("infocode").asText()) {
                        case "10005" -> "服务器出口 IP 未加入密钥白名单（10005）";
                        case "10001" -> "服务端密钥无效或已过期（10001）";
                        case "10009" -> "密钥平台不匹配，需要 Web 服务类型（10009）";
                        case "10003","10004","10014","10019","10020","10021","10044" -> "调用额度或频率受限";
                        case "10002","10012","10041" -> "该接口尚未获得授权";
                        default -> "上游拒绝请求";
                    };
                    throw new Unavailable(message);
                }
                return node;
            }
        } catch(Unavailable e){throw e;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new Unavailable("请求已中断");}
        catch(Exception e){throw new Unavailable("连接或响应异常");}
    }
    private static double[] coordinates(String value) {
        String[] parts=value.split(",");if(parts.length!=2)throw new IllegalArgumentException();
        double lon=Double.parseDouble(parts[0]),lat=Double.parseDouble(parts[1]);
        if(!Double.isFinite(lon)||!Double.isFinite(lat)||lon<73||lon>136||lat<3||lat>54)throw new IllegalArgumentException();
        return new double[]{lon,lat};
    }
    private static double km(double[] a,double[] b) {
        double h=Math.pow(Math.sin(Math.toRadians(a[1]-b[1])/2),2)+Math.cos(Math.toRadians(a[1]))*Math.cos(Math.toRadians(b[1]))*Math.pow(Math.sin(Math.toRadians(a[0]-b[0])/2),2);
        return 6371*2*Math.asin(Math.sqrt(Math.min(1,h)));
    }
    Result fetch(String city)throws Unavailable {
        var geocodes=request("/v3/geocode/geo","address="+enc(city)).path("geocodes");
        if(!geocodes.isArray()||geocodes.size()!=1)throw new Unavailable("定位无结果或有歧义，请填写省市区");
        var geocode=geocodes.get(0);double[] center;
        try{center=coordinates(geocode.path("location").asText());}catch(Exception e){throw new Unavailable("定位坐标无效");}
        String name=geocode.path("formatted_address").asText(city);
        var location=new PlanningEvidence.Location(name,center[1],center[0],"Asia/Shanghai",
                "https://uri.amap.com/marker?position="+center[0]+","+center[1]+"&name="+enc(name)+"&coordinate=gaode&src=yangbin-ai-agent","GCJ-02");
        var pois=request("/v3/place/around","location="+center[0]+","+center[1]+"&radius=3000&types=050000%7C110101%7C140100&offset=12&page=1&extensions=all&sortrule=distance").path("pois");
        if(!pois.isArray())throw new Unavailable("地点响应无效");
        var places=new ArrayList<PlanningEvidence.Place>();var seen=new HashSet<String>();
        for(var poi:pois){
            String id=poi.path("id").asText(),title=poi.path("name").asText(),type=poi.path("typecode").asText();
            String category=type.startsWith("0505")?"cafe":type.startsWith("05")?"restaurant":type.startsWith("110101")?"park":type.startsWith("1401")?"museum":"";
            if(!id.matches("[A-Za-z0-9]{1,40}")||title.isBlank()||category.isEmpty()||seen.contains(id))continue;
            double[] coords;try{coords=coordinates(poi.path("location").asText());}catch(Exception e){continue;}
            if(km(center,coords)>3.1)continue;
            String address=poi.path("pname").asText()+poi.path("cityname").asText()+poi.path("adname").asText()+poi.path("address").asText();
            places.add(new PlanningEvidence.Place("amap/"+id,title,category,coords[1],coords[0],address,
                    poi.path("business").path("opentime_today").asText(),
                    "https://uri.amap.com/marker?poiid="+enc(id)+"&src=yangbin-ai-agent","GCJ-02"));
            seen.add(id);if(places.size()==12)break;
        }
        return new Result(location,List.copyOf(places));
    }
}
