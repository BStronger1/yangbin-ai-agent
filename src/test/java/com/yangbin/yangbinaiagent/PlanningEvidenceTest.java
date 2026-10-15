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
}
