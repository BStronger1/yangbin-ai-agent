package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.planner.*;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real durable repair ledger with controlled planner hooks; no paid model requests or network. */
class PlanRepairServiceTest {
    static final ObjectMapper JSON=new ObjectMapper();
    @TempDir Path directory;
    Path repairDirectory;
    PlannerService planner;
    PlanRepairService service;
    UserContext.Scope identity;
    String id;
    PlannerService.Plan original;
    PlannerService.Itinerary corrected;
    AtomicReference<PlannerService.Plan> latest;
    PlanningEvidence.Snapshot evidence;

    @BeforeEach void setup() throws Exception {
        identity=UserContext.open(TaskWorkflowTest.A);
        id=UUID.randomUUID().toString();repairDirectory=directory.resolve("repairs");
        var place=new PlanningEvidence.Place("node/42","中山公园","park",31.22,121.45,"上海市测试路","","https://www.openstreetmap.org/node/42");
        evidence=new PlanningEvidence.Snapshot("2026-10-16T00:00:00Z",null,null,List.of(place),"unavailable","available",List.of("价格与营业状态未核实"));
        var bad=itinerary("已核实票价为50元。已确认当天正常营业。",true);
        corrected=itinerary("费用为估算，出行前确认营业与实际价格。",true);
        original=new PlannerService.Plan(id,1,"2026-10-16T00:00:00Z",TaskWorkflowTest.REQUIREMENTS,bad,List.of(),TaskWorkflowTest.A.id(),evidence,
                PlanQualityService.assess(TaskWorkflowTest.REQUIREMENTS,bad,evidence));
        latest=new AtomicReference<>(original);
        planner=mock(PlannerService.class);
        when(planner.latest(anyString())).thenAnswer(call->{
            if(!TaskWorkflowTest.A.equals(UserContext.current())||!id.equals(call.getArgument(0)))throw new IllegalArgumentException("方案不存在");
            return latest.get();
        });
        when(planner.get(anyString(),anyInt())).thenAnswer(call->{
            if(!TaskWorkflowTest.A.equals(UserContext.current())||!id.equals(call.getArgument(0)))throw new IllegalArgumentException("方案不存在");
            return ((Integer)call.getArgument(1))==1?original:latest.get();
        });
        when(planner.repairDraft(any(),any())).thenReturn(corrected);
        when(planner.prepareRepair(any(),any())).thenAnswer(call->call.getArgument(1));
        when(planner.publishRepair(any(),any(),any())).thenAnswer(call->publish(call.getArgument(0),call.getArgument(1),call.getArgument(2)));
        service=new PlanRepairService(JSON,planner,repairDirectory.toString());
    }
    @AfterEach void close(){try{service.close();}finally{identity.close();}}
    PlannerService.Itinerary itinerary(String note,boolean concrete) {
        return new PlannerService.Itinerary("两人出行","费用只作估算",List.of(new PlannerService.Stop("14:00",concrete?"中山公园散步":"公园散步",50,note,concrete?"node/42":null)),List.of("如果下雨，则选择室内活动。"),List.of());
    }
    PlannerService.Plan publish(PlannerService.Plan from,PlannerService.Itinerary candidate,PlanQualityService.Report quality) {
        var updated=new PlannerService.Plan(from.id(),from.version()+1,"2026-10-16T00:10:00Z",from.requirements(),candidate,from.sources(),from.owner(),from.evidence(),quality);
        latest.set(updated);return updated;
    }
    PlanRepairService.State stored() throws Exception {return JSON.readValue(repairDirectory.resolve(id+".json").toFile(),PlanRepairService.State.class);}
    static long warnings(PlanQualityService.Report report){return report.findings().stream().filter(f->"warning".equals(f.severity())).count();}

    @Test void attemptIsDurablyConsumedBeforeModelAndSuccessfulRepairIsIdempotent() throws Exception {
        doAnswer(call->{
            assertThat(Thread.holdsLock(planner)).as("paid call must release planner monitor").isFalse();
            assertThat(Thread.holdsLock(service)).as("paid call must release repair monitor").isFalse();
            var durable=stored();assertThat(durable.status).isEqualTo("RUNNING");assertThat(durable.attempts).isEqualTo(1);
            return corrected;
        }).when(planner).repairDraft(any(),any());
        assertThat(service.get(id).status()).isEqualTo("NOT_STARTED");
        var repaired=service.repair(id,1);
        assertThat(repaired.status()).isEqualTo("REPAIRED");assertThat(repaired.attempts()).isEqualTo(1);
        assertThat(repaired.sourceVersion()).isEqualTo(1);assertThat(repaired.targetVersion()).isEqualTo(2);
        assertThat(warnings(repaired.beforeQuality())).isEqualTo(2);assertThat(warnings(repaired.afterQuality())).isZero();
        assertThat(repaired.beforeItinerary()).isEqualTo(original.itinerary());assertThat(repaired.afterItinerary()).isEqualTo(corrected);
        assertThat(service.repair(id,1)).isEqualTo(repaired);
        service.close();service=new PlanRepairService(JSON,planner,repairDirectory.toString());
        assertThat(service.get(id)).isEqualTo(repaired);
        verify(planner,times(1)).repairDraft(any(),any());verify(planner,times(1)).publishRepair(any(),any(),any());
    }

    @Test void concurrentDuplicatePostCannotStartAnotherModelRequest() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IOException("test latch expired");return corrected;})
                .when(planner).repairDraft(any(),any());
        try(var workers=Executors.newFixedThreadPool(2)) {
            Future<PlanRepairService.View> first=workers.submit(()->{try(var ignored=UserContext.open(TaskWorkflowTest.A)){return service.repair(id,1);}});
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                Future<PlanRepairService.View> repeated=workers.submit(()->{try(var ignored=UserContext.open(TaskWorkflowTest.A)){return service.repair(id,1);}});
                assertThat(repeated.get(5,TimeUnit.SECONDS).status()).isEqualTo("RUNNING");
            } finally {release.countDown();}
            assertThat(first.get(5,TimeUnit.SECONDS).status()).isEqualTo("REPAIRED");
        }
        verify(planner,times(1)).repairDraft(any(),any());verify(planner,times(1)).publishRepair(any(),any(),any());
    }

    @Test void fewerWarningsMayBeAcceptedWithoutPretendingEveryWarningIsFixed() throws Exception {
        when(planner.repairDraft(any(),any())).thenReturn(itinerary("已核实票价为50元。",true));
        var result=service.repair(id,1);
        assertThat(result.status()).isEqualTo("REPAIRED");assertThat(warnings(result.beforeQuality())).isEqualTo(2);
        assertThat(warnings(result.afterQuality())).isEqualTo(1);assertThat(result.afterQuality().status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.afterQuality().matchedStops()).isEqualTo(1);
    }

    @Test void unchangedWarningsAreRejectedAndOriginalVersionRemains() throws Exception {
        when(planner.repairDraft(any(),any())).thenReturn(original.itinerary());
        var result=service.repair(id,1);assertThat(result.status()).isEqualTo("REJECTED");assertThat(latest.get()).isEqualTo(original);
        assertThat(result.targetVersion()).isNull();assertThat(result.afterQuality()).isNotNull();
        assertThat(service.repair(id,1).status()).isEqualTo("REJECTED");
        verify(planner,times(1)).repairDraft(any(),any());verify(planner,never()).publishRepair(any(),any(),any());
    }

    @Test void reducedWarningCountWithNewWarningCodeIsRejected() throws Exception {
        when(planner.repairDraft(any(),any())).thenReturn(itinerary("疫情期间请佩戴口罩。",true));
        var result=service.repair(id,1);assertThat(warnings(result.afterQuality())).isEqualTo(1);
        assertThat(result.afterQuality().findings()).anyMatch(f->"HEALTH_RESTRICTION_UNSUPPORTED".equals(f.code()));
        assertThat(result.status()).isEqualTo("REJECTED");assertThat(latest.get()).isEqualTo(original);
        verify(planner,never()).publishRepair(any(),any(),any());
    }

    @Test void removingGroundedStopsToReduceWarningsIsRejected() throws Exception {
        when(planner.repairDraft(any(),any())).thenReturn(itinerary("费用为估算。",false));
        var result=service.repair(id,1);assertThat(warnings(result.afterQuality())).isZero();
        assertThat(result.afterQuality().matchedStops()).isZero();assertThat(result.status()).isEqualTo("REJECTED");
        verify(planner,never()).publishRepair(any(),any(),any());
    }

    @Test void onlyCurrentVersionWithEvidenceAndWarningsCanConsumeAttempt() throws Exception {
        assertThatThrownBy(()->service.repair(id,2)).isInstanceOf(IllegalStateException.class);
        latest.set(new PlannerService.Plan(id,1,original.createdAt(),original.requirements(),corrected,List.of(),original.owner(),evidence,null));
        assertThatThrownBy(()->service.repair(id,1)).isInstanceOfAny(IllegalStateException.class,IllegalArgumentException.class);
        latest.set(new PlannerService.Plan(id,1,original.createdAt(),original.requirements(),original.itinerary(),List.of(),original.owner(),null,null));
        assertThatThrownBy(()->service.repair(id,1)).isInstanceOfAny(IllegalStateException.class,IllegalArgumentException.class);
        assertThat(Files.exists(repairDirectory.resolve(id+".json"))).isFalse();
        verify(planner,never()).repairDraft(any(),any());
    }

    @Test void latestVersionIsRecheckedBeforeReservingThePaidAttempt() throws Exception {
        var revised=new PlannerService.Plan(id,2,"2026-10-16T00:05:00Z",original.requirements(),original.itinerary(),original.sources(),original.owner(),original.evidence(),original.quality());
        AtomicInteger reads=new AtomicInteger();
        // A concurrent revision becomes visible after the request's preliminary ownership/version read.
        doAnswer(call->reads.getAndIncrement()==0?original:revised).when(planner).latest(id);
        assertThatThrownBy(()->service.repair(id,1)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(repairDirectory.resolve(id+".json"))).isFalse();
        verify(planner,never()).repairDraft(any(),any());verify(planner,never()).publishRepair(any(),any(),any());
    }

    @Test void anotherOwnerCannotInspectOrTriggerRepair() throws Exception {
        try(var ignored=UserContext.open(TaskWorkflowTest.B)) {
            assertThatThrownBy(()->service.get(id)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->service.repair(id,1)).isInstanceOf(IllegalArgumentException.class);
        }
        service.repair(id,1);
        try(var ignored=UserContext.open(TaskWorkflowTest.B)) {
            assertThatThrownBy(()->service.get(id)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->service.repair(id,1)).isInstanceOf(IllegalArgumentException.class);
        }
        verify(planner,times(1)).repairDraft(any(),any());
    }

    @Test void failedInitialLedgerWriteNeverCallsModel() throws Exception {
        Files.delete(repairDirectory);Files.writeString(repairDirectory,"not a directory");
        assertThat(service.repair(id,1).status()).isEqualTo("FAILED");
        verify(planner,never()).repairDraft(any(),any());
        Files.delete(repairDirectory);Files.createDirectory(repairDirectory);
        assertThat(service.get(id).status()).isNotEqualTo("REPAIRED");
        verify(planner,never()).repairDraft(any(),any());
    }

    @Test void providerFailureIsSanitizedAndCannotBeRetriedForAnotherPaidRequest() throws Exception {
        doThrow(new IOException("https://private.provider.test?key=FAKE_SECRET_KEY private exception"))
                .when(planner).repairDraft(any(),any());
        var failed=service.repair(id,1);assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(JSON.writeValueAsString(failed)).doesNotContain("FAKE_SECRET_KEY","private.provider.test","private exception");
        assertThat(service.repair(id,1).status()).isEqualTo("FAILED");
        service.close();service=new PlanRepairService(JSON,planner,repairDirectory.toString());
        assertThat(service.repair(id,1).status()).isEqualTo("FAILED");verify(planner,times(1)).repairDraft(any(),any());
    }

    @Test void interruptedRunningLedgerSurvivesRestartAndLateModelCannotPublish() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IOException("test latch expired");return corrected;})
                .when(planner).repairDraft(any(),any());
        var old=service;
        try(var worker=Executors.newSingleThreadExecutor()) {
            Future<PlanRepairService.View> pending=worker.submit(()->{try(var ignored=UserContext.open(TaskWorkflowTest.A)){return old.repair(id,1);}});
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                old.close();service=new PlanRepairService(JSON,planner,repairDirectory.toString());
                assertThat(service.get(id).status()).isEqualTo("INTERRUPTED");
                assertThat(service.repair(id,1).status()).isEqualTo("INTERRUPTED");
            } finally {release.countDown();}
            pending.get(5,TimeUnit.SECONDS);
        }
        assertThat(service.get(id).status()).isEqualTo("INTERRUPTED");verify(planner,times(1)).repairDraft(any(),any());
        verify(planner,never()).publishRepair(any(),any(),any());
    }

    @Test void anotherPlanForSameOwnerCannotBypassSingleActiveRepairLimit() throws Exception {
        String otherId=UUID.randomUUID().toString();
        var other=new PlannerService.Plan(otherId,1,original.createdAt(),original.requirements(),original.itinerary(),original.sources(),original.owner(),original.evidence(),original.quality());
        doReturn(other).when(planner).latest(otherId);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IOException("test latch expired");return corrected;})
                .when(planner).repairDraft(any(),any());
        try(var worker=Executors.newSingleThreadExecutor()) {
            Future<PlanRepairService.View> active=worker.submit(()->{try(var ignored=UserContext.open(TaskWorkflowTest.A)){return service.repair(id,1);}});
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->service.repair(otherId,1)).isInstanceOf(IllegalStateException.class);
                assertThat(Files.exists(repairDirectory.resolve(otherId+".json"))).isFalse();
            } finally {release.countDown();}
            assertThat(active.get(5,TimeUnit.SECONDS).status()).isEqualTo("REPAIRED");
        }
        verify(planner,times(1)).repairDraft(any(),any());
    }

    @Test void publishingStateResumesAfterRestartWithoutAnotherModelCall() throws Exception {
        AtomicInteger publishes=new AtomicInteger();
        doAnswer(call->{
            if(publishes.getAndIncrement()==0)throw new IOException("temporary publication outage");
            return publish(call.getArgument(0),call.getArgument(1),call.getArgument(2));
        }).when(planner).publishRepair(any(),any(),any());
        var pending=service.repair(id,1);assertThat(pending.status()).isEqualTo("PUBLISHING");
        assertThat(stored().status).isEqualTo("PUBLISHING");assertThat(stored().candidate).isEqualTo(corrected);
        service.close();service=new PlanRepairService(JSON,planner,repairDirectory.toString());
        var restored=service.get(id);assertThat(restored.status()).isEqualTo("REPAIRED");assertThat(restored.targetVersion()).isEqualTo(2);
        verify(planner,times(1)).repairDraft(any(),any());assertThat(publishes.get()).isEqualTo(2);
    }
    @Test void candidateCheckpointFailureCannotPublishOrPermitAnotherModelAfterRestart() throws Exception {
        service.close();
        var failingMapper=new ObjectMapper(){
            @Override public void writeValue(java.io.File file,Object value) throws IOException {
                if(value instanceof PlanRepairService.State state&&"PUBLISHING".equals(state.status))throw new IOException("simulated candidate ledger outage");
                super.writeValue(file,value);
            }
        };
        service=new PlanRepairService(failingMapper,planner,repairDirectory.toString());
        assertThat(service.repair(id,1).status()).isEqualTo("PUBLISHING");
        assertThat(stored().status).isEqualTo("RUNNING");
        verify(planner,never()).publishRepair(any(),any(),any());
        service.close();service=new PlanRepairService(JSON,planner,repairDirectory.toString());
        assertThat(service.get(id).status()).isEqualTo("INTERRUPTED");
        assertThat(service.repair(id,1).status()).isEqualTo("INTERRUPTED");
        verify(planner,times(1)).repairDraft(any(),any());verify(planner,never()).publishRepair(any(),any(),any());
    }

    @Test void successfulPublicationWithLostFinalCheckpointOnlyReplaysPublication() throws Exception {
        service.close();AtomicBoolean failOnce=new AtomicBoolean(true);
        var failingMapper=new ObjectMapper(){
            @Override public void writeValue(java.io.File file,Object value) throws IOException {
                if(value instanceof PlanRepairService.State state&&"REPAIRED".equals(state.status)&&failOnce.getAndSet(false))throw new IOException("simulated completed ledger outage");
                super.writeValue(file,value);
            }
        };
        service=new PlanRepairService(failingMapper,planner,repairDirectory.toString());
        assertThat(service.repair(id,1).status()).isEqualTo("PUBLISHING");
        assertThat(latest.get().version()).isEqualTo(2);assertThat(stored().status).isEqualTo("PUBLISHING");
        service.close();service=new PlanRepairService(JSON,planner,repairDirectory.toString());
        assertThat(service.get(id).status()).isEqualTo("REPAIRED");assertThat(latest.get().version()).isEqualTo(2);
        verify(planner,times(1)).repairDraft(any(),any());verify(planner,times(2)).publishRepair(any(),any(),any());
    }

}
