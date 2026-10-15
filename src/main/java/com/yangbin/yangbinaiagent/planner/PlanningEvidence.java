package com.yangbin.yangbinaiagent.planner;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.net.*;import java.net.http.*;import java.time.*;import java.util.*;

/** Public sources only; no user preferences or conversation text is sent to providers. */
@Service
public class PlanningEvidence {
 public record Location(String name,double latitude,double longitude,String timezone,String url,String coordinateSystem){ public Location(String n,double lat,double lon,String tz,String u){this(n,lat,lon,tz,u,"WGS84");} }
 public record Weather(String date,String description,double minC,double maxC,Integer rainProbability,String url){}
 public record Place(String id,String name,String category,double latitude,double longitude,String address,String openingHours,String url,String coordinateSystem){ public Place(String id,String n,String c,double lat,double lon,String a,String hours,String u){this(id,n,c,lat,lon,a,hours,u,"WGS84");} }
 public record Snapshot(String fetchedAt,Location location,Weather weather,List<Place> places,String weatherStatus,String placesStatus,List<String> notices){}
 private final ObjectMapper json;private final boolean enabled;private final String geo,forecast,placesUrl;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NEVER).build();
 private final Map<String,Snapshot> cache=new HashMap<>();
 private final AmapPlaces amap;
 public PlanningEvidence(ObjectMapper json,boolean enabled,String geo,String forecast,String placesUrl){this(json,enabled,geo,forecast,placesUrl,"","https://restapi.amap.com");}
 @Autowired
 public PlanningEvidence(ObjectMapper json,@Value("${app.planning.live-enabled:false}") boolean enabled,@Value("${app.planning.geocoding-url:https://geocoding-api.open-meteo.com/v1/search}") String geo,@Value("${app.planning.forecast-url:https://api.open-meteo.com/v1/forecast}") String forecast,@Value("${app.planning.places-url:https://photon.komoot.io/reverse}") String placesUrl,@Value("${app.planning.amap-key:${AMAP_API_KEY:}}") String amapKey,@Value("${app.planning.amap-url:https://restapi.amap.com}") String amapUrl){this.json=json;this.enabled=enabled;this.geo=geo;this.forecast=forecast;this.placesUrl=placesUrl;this.amap=new AmapPlaces(json,amapKey,amapUrl);}

 private static String enc(String text){return URLEncoder.encode(text,java.nio.charset.StandardCharsets.UTF_8);}
 private JsonNode get(String url)throws Exception{var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(18)).header("User-Agent","yangbin-ai-agent/1.0 (learning project)").GET().build();var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());try(var stream=response.body()){if(response.statusCode()!=200)throw new IllegalStateException("Provider unavailable");byte[] bytes=stream.readNBytes(2000001);if(bytes.length>2000000)throw new IllegalStateException("Provider response too large");return json.readTree(bytes);}}
 public static String weatherName(int c){return switch(c){case 0->"晴";case 1,2->"晴间多云";case 3->"阴";case 45,48->"雾";case 51,53,55,56,57->"毛毛雨";case 61,63,65,66,67,80,81,82->"雨";case 71,73,75,77,85,86->"雪";case 95,96,99->"雷暴";default->"天气代码 "+c;};}
 public synchronized Snapshot fetch(String city,String date){
  if(city==null||city.isBlank()||city.length()>80)throw new IllegalArgumentException("请填写城市");LocalDate day;try{day=LocalDate.parse(date);}catch(Exception e){throw new IllegalArgumentException("日期无效");}
  String key=city.strip()+":"+date;Snapshot old=cache.get(key);if(old!=null&&Instant.parse(old.fetchedAt()).isAfter(Instant.now().minusSeconds(amap.configured()&&old.notices().stream().anyMatch(n->n.startsWith("高德暂不可用"))?60:600)))return old;
  var notes=new ArrayList<String>();String now=Instant.now().toString();Location location=null;Weather weather=null;var places=new ArrayList<Place>();String ws="unavailable",ps="unavailable";
  notes.add("天气为预报，不是出行时实况；地图地点可能缺漏或过时。价格、路线耗时和当天营业状态未核实。");
  if(!enabled)return new Snapshot(now,null,null,List.of(),"disabled","disabled",List.of("实时查询尚未启用；只生成活动类型和估算费用。"));
  AmapPlaces.Result amapResult=null;
  if(amap.configured())try{amapResult=amap.fetch(city);}catch(AmapPlaces.Unavailable e){notes.add("高德暂不可用："+e.getMessage()+"。本次使用开放地图回退；稍后重新查询可重试。");}
  try{
   String query=city.strip().replaceAll("市$","");String url=geo+"?name="+enc(query)+"&count=10&language=zh&countryCode=CN&format=json";
   var results=get(url).path("results");var candidates=new ArrayList<JsonNode>();for(var item:results)if(item.path("country_code").asText().equals("CN")&&item.path("feature_code").asText().startsWith("PPL"))candidates.add(item);
   var exact=candidates.stream().filter(c->c.path("name").asText().replaceAll("市$","").equals(query)).toList();if(!exact.isEmpty())candidates=new ArrayList<>(exact);
   var cities=candidates.stream().filter(c->c.path("feature_code").asText().matches("PPLC|PPLA[0-9]?" )).toList();if(!cities.isEmpty())candidates=new ArrayList<>(cities);
   if(candidates.size()!=1){notes.add(candidates.isEmpty()?"未匹配到中国城市，请使用城市名称或拼音；未使用猜测坐标。":"城市存在多个匹配，请补充省份（例如：城市, 省份），再查询。");throw new IllegalStateException("City not uniquely resolved");}
   var cityNode=candidates.get(0);double lat=cityNode.path("latitude").asDouble(),lon=cityNode.path("longitude").asDouble();String zone=cityNode.path("timezone").asText("Asia/Shanghai");ZoneId.of(zone);
   location=new Location(cityNode.path("name").asText()+" · "+cityNode.path("admin1").asText()+" · 中国",lat,lon,zone,"https://www.geonames.org/"+cityNode.path("id").asLong());
   LocalDate today=LocalDate.now(ZoneId.of(zone));
   if(day.isBefore(today)||day.isAfter(today.plusDays(15))){ws="out_of_range";notes.add("所选日期不在未来 16 天预报范围内，不能提供该日天气。");}
   else try{
    String weatherUrl=forecast+"?latitude="+lat+"&longitude="+lon+"&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone="+enc(zone)+"&forecast_days=16";
    var daily=get(weatherUrl).path("daily");int index=-1;for(int i=0;i<daily.path("time").size();i++)if(daily.path("time").get(i).asText().equals(date))index=i;
    if(index<0||!daily.path("weather_code").path(index).isNumber()||!daily.path("temperature_2m_min").path(index).isNumber()||!daily.path("temperature_2m_max").path(index).isNumber())throw new IllegalStateException();
    var rain=daily.path("precipitation_probability_max").path(index);weather=new Weather(date,weatherName(daily.path("weather_code").get(index).asInt()),daily.path("temperature_2m_min").get(index).asDouble(),daily.path("temperature_2m_max").get(index).asDouble(),rain.isNumber()?rain.asInt():null,weatherUrl);ws="available";
   }catch(Exception e){notes.add("天气服务暂不可用，本次不推断天气。");}
   if(amapResult==null)try{
    var result=get(placesUrl+"?lon="+lon+"&lat="+lat+"&radius=3&limit=12&osm_tag=amenity:cafe&osm_tag=amenity:restaurant&osm_tag=leisure:park&osm_tag=tourism:museum");
    if(!result.path("features").isArray())throw new IllegalStateException("Invalid map response");
    for(var node:result.path("features")){
     var tags=node.path("properties");String type=switch(tags.path("osm_type").asText()){case "N"->"node";case "W"->"way";case "R"->"relation";default->"";};long id=tags.path("osm_id").asLong();String name=tags.path("name").asText(),category=tags.path("osm_value").asText();var coords=node.path("geometry").path("coordinates");
     if(type.isBlank()||id<=0||name.isBlank()||coords.size()!=2||!Set.of("cafe","restaurant","park","museum").contains(category))continue;
     double plat=coords.get(1).asDouble(),plon=coords.get(0).asDouble();double km=6371*2*Math.asin(Math.sqrt(Math.pow(Math.sin(Math.toRadians(plat-lat)/2),2)+Math.cos(Math.toRadians(lat))*Math.cos(Math.toRadians(plat))*Math.pow(Math.sin(Math.toRadians(plon-lon)/2),2)));if(!Double.isFinite(km)||km>3.1)continue;
     String address=tags.path("district").asText()+" "+tags.path("street").asText()+" "+tags.path("housenumber").asText();
     places.add(new Place(type+"/"+id,name,category,plat,plon,address.strip(),tags.path("extra").path("opening_hours").asText(),"https://www.openstreetmap.org/"+type+"/"+id));if(places.size()>=12)break;
    }ps=places.isEmpty()?"empty":"available";if(places.isEmpty())notes.add("城市中心约 3 公里范围内未查到候选地点；这不表示实际没有地点。");
   }catch(Exception e){notes.add("开放地图查询暂不可用，本次不提供具体地点依据。");}
  }catch(Exception e){notes.add("城市定位服务暂不可用，未查询天气和地点。");}
  if(amapResult!=null){
   location=amapResult.location();places=new ArrayList<>(amapResult.places());ps=places.isEmpty()?"empty":"available";
   notes.removeIf(n->n.startsWith("城市中心约")||n.startsWith("开放地图查询")||n.startsWith("城市定位服务")||n.startsWith("未匹配到中国城市")||n.startsWith("城市存在多个匹配"));
   notes.add("地点与定位来自高德地图；天气使用独立的城市级预报。地点信息可能变化，出行前请在地图中确认。");
   if(weather==null&&ws.equals("unavailable"))notes.add("未获取到城市天气预报，本次不推断天气。");
   if(places.isEmpty())notes.add("高德在定位点约 3 公里内未返回候选地点；这不表示实际没有地点。");
  }
  if(places.stream().anyMatch(p->!p.id().startsWith("amap/")))notes.add("地点由 Photon 检索 OpenStreetMap 数据，© OpenStreetMap contributors；定位 GeoNames，坐标系 WGS84。");
  Snapshot value=new Snapshot(now,location,weather,List.copyOf(places),ws,ps,List.copyOf(notes));if(cache.size()>=128)cache.clear();cache.put(key,value);return value;
 }
}
