package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yangbin.yangbinaiagent.controller.PlanRouteController;
import com.yangbin.yangbinaiagent.planner.*;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanRouteServiceTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String OK = "{\"status\":\"1\",\"route\":{\"paths\":[{\"distance\":\"1200\",\"duration\":\"900\"}]}}";
    @TempDir Path root;
    HttpServer server;
    ExecutorService threads;
    String base;
    volatile String response = OK, query;
    volatile int httpStatus = 200;
    volatile boolean failFirst, slow;
    AtomicInteger calls = new AtomicInteger();
    @BeforeEach void start() throws Exception {
        threads = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(threads);
        server.createContext("/v3/direction/walking", x -> {
            int n = calls.incrementAndGet(); query = x.getRequestURI().getRawQuery();
            String body = failFirst && n == 1 ? "{\"status\":\"0\"}" : response;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try {
                x.getResponseHeaders().set("Location", base + "/redirect-must-not-follow");
                x.sendResponseHeaders(httpStatus, bytes.length);
                if (slow) try { Thread.sleep(5500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                x.getResponseBody().write(bytes);
            } finally { x.close(); }
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); threads.shutdownNow(); }
    PlanRouteService service() { return new PlanRouteService(JSON, true, "fixture-key", base); }
    PlanningEvidence.Place place(String id, double lat, double lon, String system) {
        return new PlanningEvidence.Place(id, "测试地点" + id, "cafe", lat, lon, "", "", "https://fixture.invalid", system);
    }
    List<PlanningEvidence.Place> places() {
        return List.of(place("amap/A",31.2200001,121.4500001,"GCJ-02"), place("amap/B",31.23,121.46,"GCJ-02"),
            place("amap/C",31.24,121.47,"GCJ-02"));
    }
    PlannerService.Plan plan(String mode, List<PlanningEvidence.Place> places) {
        var req = new PlannerService.Requirements("上海",500,"2026-11-14","14:00",4,"私人偏好不应发给地图",mode,"");
        var stops = List.of(new PlannerService.Stop("14:00","测试地点amap/A",10,"","amap/A"),
            new PlannerService.Stop("14:10","测试地点amap/B",10,"","amap/B"),
            new PlannerService.Stop("14:30","测试地点amap/C",10,"","amap/C"));
        var evidence = new PlanningEvidence.Snapshot("2026-10-16T10:00:00Z",null,null,places,"unavailable","available",List.of());
        return new PlannerService.Plan(UUID.randomUUID().toString(),1,"2026-10-16T10:00:00Z",req,
            new PlannerService.Itinerary("路线测试","",stops,List.of(),List.of()),List.of(),TaskWorkflowTest.A.id(),evidence);
    }
    PlannerService.Plan withStops(PlannerService.Plan p, List<PlannerService.Stop> stops) {
        return new PlannerService.Plan(p.id(),p.version(),p.createdAt(),p.requirements(),
            new PlannerService.Itinerary("路线测试","",stops,List.of(),List.of()),p.sources(),p.owner(),p.evidence());
    }
    @Test void routeUsesLonLatSecondsAndConservativeTimingWithoutSendingPrivateText() throws Exception {
        var p = plan("步行", places()); var report = service().inspect(p);
        assertThat(report.status()).isEqualTo("AVAILABLE"); assertThat(report.upstreamRequests()).isEqualTo(2);
        var first = report.legs().get(0);
        assertThat(first.distanceMeters()).isEqualTo(1200); assertThat(first.durationSeconds()).isEqualTo(900);
        assertThat(first.startGapSeconds()).isEqualTo(600); assertThat(first.remainingAfterWalkingSeconds()).isEqualTo(-300);
        assertThat(first.timing()).isEqualTo("INSUFFICIENT_TIME");
        assertThat(report.legs().get(1).timing()).isEqualTo("NEEDS_STAY_TIME");
        assertThat(report.legs().get(1).remainingAfterWalkingSeconds()).isEqualTo(300);
        assertThat(query).isEqualTo("origin=121.460000,31.230000&destination=121.470000,31.240000&output=JSON&key=fixture-key");
        assertThat(JSON.writeValueAsString(report)).doesNotContain("fixture-key", "key=", "私人偏好");
        Path output = Path.of("target/route-review/example.json"); Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }
    @Test void otherTransportAndInvalidScheduleAreReferenceOnly() {
        for (String mode : List.of("公共交通","自驾","打车","步行和公交")) {
            assertThat(service().inspect(plan(mode, places())).legs())
                .allMatch(l -> l.timing().equals("REFERENCE_ONLY") && l.remainingAfterWalkingSeconds() == null);
        }
        var p = plan("步行",places()); var stops = new ArrayList<>(p.itinerary().stops());
        stops.set(1,new PlannerService.Stop("13:00","",0,"","amap/B"));
        assertThat(service().inspect(withStops(p,stops)).legs().get(0).timing()).isEqualTo("REFERENCE_ONLY");
    }
    @Test void disabledMissingAndAmbiguousCoordinatesNeverCallProvider() {
        var p = plan("步行",places());
        assertThat(new PlanRouteService(JSON,false,"fixture-key",base).inspect(p).legs()).allMatch(l -> l.status().equals("DISABLED"));
        assertThat(new PlanRouteService(JSON,true,"",base).inspect(p).legs()).allMatch(l -> l.status().equals("NOT_CONFIGURED"));
        for (var bad : List.of(place("amap/B",31.23,121.46,"WGS84"), place("amap/B",31.23,121.46,null),
            place("amap/B",Double.NaN,121.46,"GCJ-02"), place("amap/B",0,0,"GCJ-02"))) {
            assertThat(service().inspect(plan("步行",List.of(places().get(0),bad,places().get(2)))).legs())
                .allMatch(l -> l.status().equals("MISSING_COORDINATES") && l.durationSeconds() == null);
        }
        var duplicated = new ArrayList<>(places()); duplicated.add(places().get(1));
        assertThat(service().inspect(plan("步行",duplicated)).legs()).allMatch(l -> l.status().equals("MISSING_COORDINATES"));
        var legacy = new PlannerService.Plan(p.id(),1,p.createdAt(),p.requirements(),p.itinerary(),List.of(),p.owner(),null);
        assertThat(service().inspect(legacy).upstreamRequests()).isZero();
        assertThat(calls.get()).isZero();
    }
    @Test void samePlaceAndSingleStopNeedNoRequest() {
        var p=plan("步行",places());
        assertThat(service().inspect(withStops(p,List.of(p.itinerary().stops().get(0)))).status()).isEqualTo("NO_LEGS");
        var same=withStops(p,List.of(p.itinerary().stops().get(0),new PlannerService.Stop("14:30","",0,"","amap/A")));
        assertThat(service().inspect(same).legs().get(0).status()).isEqualTo("SAME_PLACE");
        assertThat(calls.get()).isZero();
    }
    @Test void invalidProviderResponsesAreUnknownAndDoNotLeakOrRetry() throws Exception {
        var p=plan("步行",places()); p=withStops(p,p.itinerary().stops().subList(0,2));
        for (String body : List.of("{\"status\":\"0\",\"info\":\"fixture-key\"}", "[]", "null", "<html>fixture-key</html>",
            "{\"status\":\"1\",\"route\":{\"paths\":[]}}",OK.replace("\"900\"","\"-1\""),
            OK.replace("\"900\"","\"0\""), OK.replace("\"900\"","[]"), OK.replace("\"900\"","\"NaN\""),
            OK.replace("\"1200\"","\"100001\""), " ".repeat(512_001))) {
            response=body; int before=calls.get(); var report=service().inspect(p);
            assertThat(report.legs().get(0).status()).isEqualTo("UNAVAILABLE");
            assertThat(report.legs().get(0).durationSeconds()).isNull();
            assertThat(calls.get()-before).isEqualTo(1);
            assertThat(JSON.writeValueAsString(report)).doesNotContain("fixture-key", "key=");
        }
        response=OK; httpStatus=302;
        assertThat(service().inspect(p).status()).isEqualTo("UNAVAILABLE");
    }
    @Test void streamingTimeoutIsBoundedAndFailureDoesNotEraseOtherLegs() {
        var p=plan("步行",places()); failFirst=true;
        var partial=service().inspect(p);
        assertThat(partial.status()).isEqualTo("PARTIAL");
        assertThat(partial.legs().get(0).status()).isEqualTo("UNAVAILABLE");
        assertThat(partial.legs().get(1).status()).isEqualTo("AVAILABLE");
        failFirst=false; slow=true; long start=System.nanoTime();
        var timed=service().inspect(withStops(p,p.itinerary().stops().subList(0,2)));
        assertThat(timed.status()).isEqualTo("UNAVAILABLE");
        assertThat((System.nanoTime()-start)/1_000_000).isLessThan(5200);
    }
    @Test @SuppressWarnings("unchecked") void controllerChecksOwnerAndVersionBeforeNetworkAndLeavesOriginalBytesUnchanged() throws Exception {
        var model=mock(ChatModel.class); var evidence=mock(PlanningEvidence.class);
        var planner=new PlannerService(model,evidence,JSON,mock(ObjectProvider.class),root.toString());
        var controller=new PlanRouteController(planner,service());
        clearInvocations(model,evidence);
        var p=plan("步行",places()); byte[] before;
        try(var scope=UserContext.open(TaskWorkflowTest.A)) {
            planner.saveTaskPlan(p.id(),p.requirements(),p.itinerary(),List.of(),p.evidence());
            Path file=root.resolve(p.id()+"-1.json"); before=Files.readAllBytes(file);
            var report=controller.routes(p.id(),1);
            assertThat(report.planId()).isEqualTo(p.id()); assertThat(report.planVersion()).isEqualTo(1);
            assertThat(Files.readAllBytes(file)).isEqualTo(before);
            int count=calls.get();
            assertThatThrownBy(()->controller.routes(p.id(),2)).isInstanceOf(IllegalArgumentException.class);
            assertThat(calls.get()).isEqualTo(count);
        }
        int count=calls.get();
        try(var scope=UserContext.open(TaskWorkflowTest.B)) {
            assertThatThrownBy(()->controller.routes(p.id(),1)).isInstanceOf(IllegalArgumentException.class).hasMessage("方案不存在");
        }
        assertThat(calls.get()).isEqualTo(count); verifyNoInteractions(model,evidence);
    }
    @Test void maximumPlanQueriesElevenLegsAndOversizedPlanIsRejected() {
        var p=plan("步行",places()); var stops=new ArrayList<PlannerService.Stop>();
        for(int i=0;i<12;i++)stops.add(new PlannerService.Stop(String.format("14:%02d",i*2),"",0,"",i%2==0?"amap/A":"amap/B"));
        assertThat(service().inspect(withStops(p,stops)).upstreamRequests()).isEqualTo(11);
        assertThat(calls.get()).isEqualTo(11);
        stops.add(stops.get(0));
        assertThatThrownBy(()->service().inspect(withStops(p,stops))).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls.get()).isEqualTo(11);
    }
}
