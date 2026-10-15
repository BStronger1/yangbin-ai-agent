package com.yangbin.yangbinaiagent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yangbin.yangbinaiagent.planner.*;
import org.junit.jupiter.api.*;
import java.net.*;import java.nio.charset.StandardCharsets;import java.time.*;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
class PlanningEvidenceTest {
 HttpServer server;String base;String date=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();AtomicInteger weatherCalls=new AtomicInteger();boolean failWeather=false,ambiguous=false;
 @BeforeEach void setup()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/geo",x->{String item="{\"id\":1796236,\"name\":\"上海\",\"country_code\":\"CN\",\"feature_code\":\"PPLA\",\"latitude\":31.22,\"longitude\":121.45,\"timezone\":\"Asia/Shanghai\"}";reply(x,200,"{\"results\":["+item+(ambiguous?","+item:"")+"]}");});server.createContext("/weather",x->{weatherCalls.incrementAndGet();reply(x,failWeather?503:200,"{\"daily\":{\"time\":[\""+date+"\"],\"weather_code\":[61],\"temperature_2m_min\":[18],\"temperature_2m_max\":[25],\"precipitation_probability_max\":[80]}}");});server.createContext("/places",x->reply(x,200,"{\"features\":[{\"properties\":{\"osm_type\":\"N\",\"osm_id\":42,\"name\":\"测试公园\",\"osm_value\":\"park\"},\"geometry\":{\"coordinates\":[121.45,31.22]}}]}"));server.start();base="http://127.0.0.1:"+server.getAddress().getPort();}
 void reply(com.sun.net.httpserver.HttpExchange x,int status,String body)throws java.io.IOException{byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);x.close();}
 @AfterEach void stop(){server.stop(0);}
 PlanningEvidence service(){return new PlanningEvidence(new ObjectMapper(),true,base+"/geo",base+"/weather",base+"/places");}
 @Test void attachesActualDateWeatherSourceAndPlaceAndCaches(){var service=service();var e=service.fetch("上海",date);assertThat(e.weatherStatus()).isEqualTo("available");assertThat(e.weather().rainProbability()).isEqualTo(80);assertThat(e.weather().date()).isEqualTo(date);assertThat(e.places().get(0).url()).isEqualTo("https://www.openstreetmap.org/node/42");assertThat(service.fetch("上海",date)).isSameAs(e);assertThat(weatherCalls.get()).isEqualTo(1);}
 @Test void outOfRangeNeverCallsWeather(){var e=service().fetch("上海",LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(30).toString());assertThat(e.weather()).isNull();assertThat(e.weatherStatus()).isEqualTo("out_of_range");assertThat(weatherCalls.get()).isZero();assertThat(e.places()).hasSize(1);}
 @Test void weatherOutageKeepsPlacesWithoutInventingForecast(){failWeather=true;var e=service().fetch("上海",date);assertThat(e.weather()).isNull();assertThat(e.weatherStatus()).isEqualTo("unavailable");assertThat(e.places()).hasSize(1);}
 @Test void ambiguousLocationDoesNotChooseSilently(){ambiguous=true;var e=service().fetch("上海",date);assertThat(e.location()).isNull();assertThat(e.places()).isEmpty();assertThat(weatherCalls.get()).isZero();}
 @Test void ungroundedPlaceIsRejected(){var e=service().fetch("上海",date);var plan=new PlannerService.Itinerary("x","x",List.of(new PlannerService.Stop("14:00","虚构",10,"x","node/999")),List.of(),List.of());assertThatThrownBy(()->PlannerService.validatePlaces(plan,e)).isInstanceOf(IllegalStateException.class);}

 PlanningEvidence amapService(){return new PlanningEvidence(new ObjectMapper(),true,base+"/geo",base+"/weather",base+"/places","test-private-key",base);}
 void amapFixtures(String status,String pois){
  server.createContext("/v3/geocode/geo",x->reply(x,200,status==null?"{\"status\":\"1\",\"geocodes\":[{\"formatted_address\":\"上海市\",\"location\":\"121.45,31.22\"}]}":status));
  server.createContext("/v3/place/around",x->reply(x,200,"{\"status\":\"1\",\"pois\":"+pois+"}"));
 }
 String validPoi(){return "{\"id\":\"B01234\",\"name\":\"高德测试咖啡馆\",\"typecode\":\"050500\",\"location\":\"121.45,31.22\",\"address\":[]}";}
 @Test void amapUsesGcjCoordinatesAndKeylessLinksWhileWeatherRemainsWgs84()throws Exception{
  amapFixtures(null,"["+validPoi()+"]");var service=amapService();var e=service.fetch("上海",date);
  assertThat(e.places()).hasSize(1);assertThat(e.places().get(0).id()).isEqualTo("amap/B01234");assertThat(e.places().get(0).coordinateSystem()).isEqualTo("GCJ-02");
  assertThat(e.location().url()).contains("coordinate=gaode");assertThat(e.location().coordinateSystem()).isEqualTo("GCJ-02");assertThat(e.weather().url()).startsWith(base+"/weather");
  assertThat(new ObjectMapper().writeValueAsString(e)).doesNotContain("test-private-key","key=");assertThat(service.fetch("上海",date)).isSameAs(e);
 }
 @Test void deniedAmapFallsBackAndDoesNotExposeRawProviderError()throws Exception{
  amapFixtures("{\"status\":\"0\",\"infocode\":\"10005\",\"info\":\"test-private-key\"}","[]");var e=amapService().fetch("上海",date);
  assertThat(e.places().get(0).id()).isEqualTo("node/42");assertThat(e.location().coordinateSystem()).isEqualTo("WGS84");assertThat(e.notices()).anyMatch(n->n.contains("10005"));assertThat(new ObjectMapper().writeValueAsString(e)).doesNotContain("test-private-key");
 }
 @Test void amapStillWorksWhenFreeGeocoderCannotResolveCity(){
  ambiguous=true;amapFixtures(null,"["+validPoi()+"]");var e=amapService().fetch("上海市静安区",date);
  assertThat(e.places().get(0).id()).startsWith("amap/");assertThat(e.weather()).isNull();assertThat(e.weatherStatus()).isEqualTo("unavailable");assertThat(e.notices()).noneMatch(n->n.contains("未查询天气和地点"));
 }
 @Test void amapFiltersInvalidFarAndDuplicatePlaces(){
  String far=validPoi().replace("B01234","FAR").replace("121.45,31.22","121.90,31.90"),bad=validPoi().replace("B01234","BAD").replace("121.45,31.22","NaN,31.22");
  amapFixtures(null,"["+validPoi()+","+validPoi()+","+far+","+bad+"]");assertThat(amapService().fetch("上海",date).places()).hasSize(1);
 }
 @Test void amapEmptyIsNotInventedOrSilentlyReplaced(){
  amapFixtures(null,"[]");var e=amapService().fetch("上海",date);assertThat(e.places()).isEmpty();assertThat(e.placesStatus()).isEqualTo("empty");assertThat(e.location().coordinateSystem()).isEqualTo("GCJ-02");
 }
 @Test void legacyEvidenceRemainsReadable()throws Exception{
  var mapper=new ObjectMapper();var original=service().fetch("上海",date);String raw=mapper.writeValueAsString(original).replace(",\"coordinateSystem\":\"WGS84\"","");var old=mapper.readValue(raw,PlanningEvidence.Snapshot.class);assertThat(old.places().get(0).url()).isEqualTo(original.places().get(0).url());
 }

 @Test void manualRetryRecoversWeatherImmediatelyInsteadOfCachingTheFailure(){
  var service=service();failWeather=true;var failed=service.fetch("北京",date);
  assertThat(failed.weatherStatus()).isEqualTo("unavailable");assertThat(failed.places()).hasSize(1);
  failWeather=false;var recovered=service.fetch("北京",date);
  assertThat(recovered.weatherStatus()).isEqualTo("available");assertThat(recovered.weather()).isNotNull();
  assertThat(recovered.notices()).noneMatch(n->n.contains("天气服务暂不可用"));assertThat(weatherCalls.get()).isEqualTo(2);
  assertThat(service.fetch("北京",date)).isSameAs(recovered);assertThat(weatherCalls.get()).isEqualTo(2);
 }
}
