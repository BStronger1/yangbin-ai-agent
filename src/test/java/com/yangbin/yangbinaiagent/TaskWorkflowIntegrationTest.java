package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.sun.net.httpserver.HttpServer;
import com.yangbin.yangbinaiagent.planner.*;
import com.yangbin.yangbinaiagent.rag.KnowledgeService;
import com.yangbin.yangbinaiagent.security.UserContext;
import com.yangbin.yangbinaiagent.tasks.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real planner, live-source HTTP parser and PDF writer; only the model and upstream data are fixtures. */
class TaskWorkflowIntegrationTest {
    @TempDir Path directory;
    HttpServer upstream;
    PlannerService planner;
    TaskWorkflow workflow;
    TaskService service;
    ChatModel model;
    TaskWorkflowTest.QueueExecutor executor;
    UserContext.Scope identity;
    final List<Path> pdfFiles = new ArrayList<>();
    final AtomicInteger weatherCalls = new AtomicInteger();
    String date = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
    PlannerService.Requirements requirements;

    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        identity = UserContext.open(TaskWorkflowTest.A);
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/geo", exchange -> reply(exchange, "{\"results\":[{\"id\":1796236,\"name\":\"上海\",\"country_code\":\"CN\",\"feature_code\":\"PPLA\",\"latitude\":31.22,\"longitude\":121.45,\"timezone\":\"Asia/Shanghai\"}]}"));
        upstream.createContext("/weather", exchange -> {weatherCalls.incrementAndGet(); reply(exchange,
                "{\"daily\":{\"time\":[\""+date+"\"],\"weather_code\":[61],\"temperature_2m_min\":[18],\"temperature_2m_max\":[25],\"precipitation_probability_max\":[80]}}");});
        upstream.createContext("/places", exchange -> reply(exchange, "{\"features\":[{\"properties\":{\"osm_type\":\"N\",\"osm_id\":42,\"name\":\"测试公园\",\"osm_value\":\"park\"},\"geometry\":{\"coordinates\":[121.45,31.22]}}]}"));
        upstream.start();
        String base="http://127.0.0.1:"+upstream.getAddress().getPort();
        var evidence = new PlanningEvidence(new ObjectMapper(), true, base+"/geo", base+"/weather", base+"/places");
        model = mock(ChatModel.class);
        var itinerary = new PlannerService.Itinerary("雨天出行计划", "参考真实查询来源",
                List.of(new PlannerService.Stop("14:00", "测试公园", 50, "价格仅估算，出行前确认", "node/42")), List.of("雨天室内备选"), List.of("请确认天气变化"));
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(new ObjectMapper().writeValueAsString(itinerary))))));
        ObjectProvider<KnowledgeService> knowledge = mock(ObjectProvider.class);
        planner = new PlannerService(model, evidence, new ObjectMapper(), knowledge, directory.resolve("plans").toString());
        workflow = spy(new TaskWorkflow(planner, evidence));
        executor = new TaskWorkflowTest.QueueExecutor();
        service = new TaskService(new ObjectMapper(), workflow, directory.resolve("tasks").toString(), executor);
        requirements = new PlannerService.Requirements("上海", 500, date, "14:00", 6, "公园", "公共交通", "");
    }
    static void reply(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] data=body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, data.length); exchange.getResponseBody().write(data); exchange.close();
    }
    @AfterEach void close() throws Exception {
        try {if (service!=null) service.close(); if(upstream!=null)upstream.stop(0); for(Path file:pdfFiles)Files.deleteIfExists(file);}
        finally {if(identity!=null)identity.close();}
    }
    Path pdf(String url) {Path result=Path.of("tmp", "pdf", TaskWorkflowTest.A.id(), url.substring(url.lastIndexOf('/')+1)); pdfFiles.add(result); return result;}

    @Test void actualWorkflowPersistsGroundedPlanAndDownloadsValidPdfForOwner() throws Exception {
        String id=service.create(UUID.randomUUID().toString(), "安排出行并参考天气，生成 PDF", requirements).id();
        executor.runNext(); var result=service.get(id);
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.evidence().weather().rainProbability()).isEqualTo(80);
        assertThat(result.evidence().places().get(0).id()).isEqualTo("node/42");
        var plan=planner.get(result.planId(), result.planVersion());
        assertThat(plan.owner()).isEqualTo(TaskWorkflowTest.A.id());
        assertThat(plan.itinerary().stops().get(0).placeId()).isEqualTo("node/42");
        assertThat(plan.evidence()).isEqualTo(result.evidence());
        assertThat(result.quality()).isEqualTo(plan.quality());
        assertThat(plan.quality().ruleVersion()).isEqualTo("grounding-v2");
        assertThat(plan.quality().matchedStops()).isEqualTo(1);
        assertThat(plan.quality().status()).isEqualTo("CHECKED_WITH_LIMITATIONS");
        Path generated=pdf(result.pdfUrl());
        assertThat(Files.readString(generated, StandardCharsets.ISO_8859_1)).startsWith("%PDF");
        try (var document=new PdfDocument(new PdfReader(generated.toString()))) {
            StringBuilder text=new StringBuilder();
            for(int page=1;page<=document.getNumberOfPages();page++)text.append(PdfTextExtractor.getTextFromPage(document.getPage(page)));
            assertThat(text.toString()).contains("雨天出行计划", "测试公园", "openstreetmap.org/node/42", "来源检查", "grounding-v2", "不是事实准确率");
        }
        try (var ignored=UserContext.open(TaskWorkflowTest.B)) {
            assertThatThrownBy(() -> planner.get(result.planId(), result.planVersion())).isInstanceOf(IllegalArgumentException.class);
        }
        verify(model, times(1)).call(any(Prompt.class));
        assertThat(weatherCalls.get()).isEqualTo(1);
    }

    @Test void lostCheckpointAfterPdfSideEffectReusesExistingFileWithoutNewModelCall() throws Exception {
        AtomicInteger exports=new AtomicInteger();
        doAnswer(invocation -> {
            String url=(String)invocation.callRealMethod();
            pdf(url);
            if(exports.getAndIncrement()==0)throw new IllegalStateException("simulated interruption after PDF write");
            return url;
        }).when(workflow).export(any());
        String id=service.create(UUID.randomUUID().toString(), "出行计划 PDF", requirements).id();
        executor.runNext(); assertThat(service.get(id).status()).isEqualTo("FAILED");
        Path generated=pdfFiles.getFirst(); byte[] original=Files.readAllBytes(generated);
        var modified=Files.getLastModifiedTime(generated);
        service.close(); executor=new TaskWorkflowTest.QueueExecutor();
        service=new TaskService(new ObjectMapper(), workflow, directory.resolve("tasks").toString(), executor);
        service.retry(id); executor.runNext();
        assertThat(service.get(id).status()).isEqualTo("SUCCEEDED");
        assertThat(Files.readAllBytes(generated)).isEqualTo(original);
        assertThat(Files.getLastModifiedTime(generated)).isEqualTo(modified);
        assertThat(planner.versions(service.get(id).planId())).hasSize(1);
        verify(model, times(1)).call(any(Prompt.class));
        verify(workflow, times(1)).save(anyString(), any(), any(), anyList(), any());
        assertThat(exports.get()).isEqualTo(2);
    }
    @Test void invalidModelDraftFailsAtModelAndRetryRequestsFreshDraft() throws Exception {
        var valid = new PlannerService.Itinerary("有效方案", "预算内", List.of(new PlannerService.Stop("14:00", "公园", 50, "估算", "node/42")), List.of(), List.of());
        var invalid = new PlannerService.Itinerary("超预算", "应拒绝", List.of(new PlannerService.Stop("14:00", "公园", 5000, "估算", "node/42")), List.of(), List.of());
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(new ObjectMapper().writeValueAsString(invalid))))),
                new ChatResponse(List.of(new Generation(new AssistantMessage(new ObjectMapper().writeValueAsString(valid))))));
        String id=service.create(UUID.randomUUID().toString(), "预算 500 元", requirements).id();
        executor.runNext(); var failed=service.get(id);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.steps().stream().filter(s -> s.id().equals("model")).findFirst().orElseThrow().status()).isEqualTo("FAILED");
        verify(workflow, never()).save(anyString(), any(), any(), anyList(), any());
        service.retry(id); executor.runNext();
        var done=service.get(id); assertThat(done.status()).isEqualTo("SUCCEEDED"); pdf(done.pdfUrl());
        assertThat(done.itinerary().title()).isEqualTo("有效方案");
        verify(model, times(2)).call(any(Prompt.class));
        assertThat(weatherCalls.get()).isEqualTo(1);
    }

    @Test void lostCheckpointAfterPlanSaveReusesOnePlanAndOneModelResponse() throws Exception {
        AtomicInteger saves=new AtomicInteger();
        doAnswer(invocation -> {
            var plan=(PlannerService.Plan)invocation.callRealMethod();
            if(saves.getAndIncrement()==0)throw new IllegalStateException("simulated interruption after plan publication");
            return plan;
        }).when(workflow).save(anyString(), any(), any(), anyList(), any());
        String id=service.create(UUID.randomUUID().toString(), "出行计划", requirements).id();
        executor.runNext(); assertThat(service.get(id).status()).isEqualTo("FAILED");
        var original=planner.get(id, 1);
        service.close(); executor=new TaskWorkflowTest.QueueExecutor();
        service=new TaskService(new ObjectMapper(), workflow, directory.resolve("tasks").toString(), executor);
        service.retry(id); executor.runNext();
        var done=service.get(id); assertThat(done.status()).isEqualTo("SUCCEEDED"); pdf(done.pdfUrl());
        assertThat(planner.get(id, 1)).isEqualTo(original);
        assertThat(planner.versions(id)).hasSize(1);
        verify(model, times(1)).call(any(Prompt.class));
        assertThat(saves.get()).isEqualTo(2);
    }

    @Test void unsupportedClaimsArePersistedAndExportedAsWarningsWithoutExtraModelCalls() throws Exception {
        var risky=new PlannerService.Itinerary("待核实示例", "疫情期间必须出示健康码",
                List.of(new PlannerService.Stop("14:00", "另一家咖啡馆", 50, "出行前确认", "node/42")), List.of(), List.of());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(new ObjectMapper().writeValueAsString(risky))))));
        String id=service.create(UUID.randomUUID().toString(), "来源检查示例", requirements).id();
        executor.runNext();var done=service.get(id);assertThat(done.status()).isEqualTo("SUCCEEDED");
        assertThat(done.quality().status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(done.quality().matchedStops()).isZero();
        assertThat(done.quality().findings()).extracting(PlanQualityService.Finding::code)
                .contains("HEALTH_RESTRICTION_UNSUPPORTED", "PLACE_NAME_MISMATCH");
        var frozen=done.quality();
        service.close();executor=new TaskWorkflowTest.QueueExecutor();
        service=new TaskService(new ObjectMapper(),workflow,directory.resolve("tasks").toString(),executor);
        assertThat(service.get(id).quality()).isEqualTo(frozen);
        assertThat(planner.get(id,1).quality()).isEqualTo(frozen);
        try(var document=new PdfDocument(new PdfReader(pdf(done.pdfUrl()).toString()))) {
            StringBuilder text=new StringBuilder();for(int page=1;page<=document.getNumberOfPages();page++)text.append(PdfTextExtractor.getTextFromPage(document.getPage(page)));
            assertThat(text.toString()).contains("发现待核实事项","来源检查","另一家咖啡馆");
        }
        verify(model,times(1)).call(any(Prompt.class));
    }

    @Test void oldSavedTaskAndPlanWithoutQualityRemainReadableWithoutBackfill() throws Exception {
        String id=service.create(UUID.randomUUID().toString(), "历史方案兼容", requirements).id();
        executor.runNext();var done=service.get(id);pdf(done.pdfUrl());service.close();
        ObjectMapper mapper=new ObjectMapper();Path planFile=directory.resolve("plans").resolve(id+"-1.json");
        var oldPlan=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(planFile.toFile());oldPlan.remove("quality");mapper.writeValue(planFile.toFile(),oldPlan);
        Path taskFile=directory.resolve("tasks").resolve(id+".json");var oldTask=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(taskFile.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)oldTask.path("plan")).remove("quality");mapper.writeValue(taskFile.toFile(),oldTask);
        byte[] originalPlan=Files.readAllBytes(planFile),originalTask=Files.readAllBytes(taskFile);
        executor=new TaskWorkflowTest.QueueExecutor();service=new TaskService(mapper,workflow,directory.resolve("tasks").toString(),executor);
        assertThat(service.get(id).status()).isEqualTo("SUCCEEDED");assertThat(service.get(id).quality()).isNull();
        assertThat(planner.get(id,1).quality()).isNull();
        assertThat(Files.readAllBytes(planFile)).isEqualTo(originalPlan);assertThat(Files.readAllBytes(taskFile)).isEqualTo(originalTask);
        verify(model,times(1)).call(any(Prompt.class));
    }

    @Test void directPlannerRevisionsStoreIndependentQualitySnapshots() throws Exception {
        var first=planner.generate(requirements,null,null);
        assertThat(first.quality()).isNotNull();assertThat(first.quality().matchedStops()).isEqualTo(1);
        var risky=new PlannerService.Itinerary("需要确认", "疫情期间必须持核酸证明",List.of(new PlannerService.Stop("14:00","测试公园",50,"估算","node/42")),List.of(),List.of());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(new ObjectMapper().writeValueAsString(risky))))));
        var second=planner.generate(requirements,first.id(),1);
        assertThat(second.version()).isEqualTo(2);assertThat(second.quality().status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(planner.get(first.id(),1).quality()).isEqualTo(first.quality());
        assertThat(first.quality().status()).isEqualTo("CHECKED_WITH_LIMITATIONS");
        verify(model,times(2)).call(any(Prompt.class));
    }

    @Test void boundedRepairPublishesSeparateVersionAndKeepsOriginalTaskPdf() throws Exception {
        var risky=new PlannerService.Itinerary("原方案", "疫情期间必须持核酸证明",
                List.of(new PlannerService.Stop("14:00","测试公园",50,"费用估算，出发前确认","node/42")),List.of(),List.of());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(new ObjectMapper().writeValueAsString(risky))))));
        String id=service.create(UUID.randomUUID().toString(), "原始任务", requirements).id();executor.runNext();
        var task=service.get(id);assertThat(task.status()).isEqualTo("SUCCEEDED");
        var original=planner.get(id,1);byte[] oldPdf=Files.readAllBytes(pdf(task.pdfUrl()));
        var corrected=new PlannerService.Itinerary("修订方案", "根据保存的日预报安排活动，实际天气出发前确认",
                List.of(new PlannerService.Stop("14:00","测试公园",50,"费用估算，出发前确认","node/42")),List.of(),List.of());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(new ObjectMapper().writeValueAsString(corrected))))));
        var before=PlanQualityService.assess(original.requirements(),original.itinerary(),original.evidence());
        var candidate=planner.prepareRepair(original,planner.repairDraft(original,before));
        var after=PlanQualityService.assess(original.requirements(),candidate,original.evidence());
        assertThat(after.status()).isEqualTo("CHECKED_WITH_LIMITATIONS");
        var published=planner.publishRepair(original,candidate,after);
        assertThat(published.version()).isEqualTo(2);assertThat(published.evidence()).isEqualTo(original.evidence());
        assertThat(planner.get(id,1)).isEqualTo(original);
        assertThat(service.get(id)).isEqualTo(task);
        assertThat(Files.readAllBytes(pdf(task.pdfUrl()))).isEqualTo(oldPdf);
        var recovered=planner.publishRepair(original,candidate,after);
        assertThat(recovered).isEqualTo(published);assertThat(planner.versions(id)).hasSize(2);
        try(var document=new PdfDocument(new PdfReader(pdf(planner.exportTaskPdf(published)).toString()))){
            StringBuilder text=new StringBuilder();for(int n=1;n<=document.getNumberOfPages();n++)text.append(PdfTextExtractor.getTextFromPage(document.getPage(n)));
            assertThat(text.toString()).contains("修订方案","版本 2","grounding-v2").doesNotContain("疫情期间必须");
        }
        verify(model,times(2)).call(any(Prompt.class));assertThat(weatherCalls.get()).isEqualTo(1);
    }

    @Test void repairCannotHideWarningsByChangingStructureOrOverwriteConcurrentRevision() throws Exception {
        var original=planner.generate(requirements,null,null);var stop=original.itinerary().stops().getFirst();
        for(var changed:List.of(new PlannerService.Stop("15:00",stop.activity(),stop.estimatedCost(),stop.note(),stop.placeId()),
                new PlannerService.Stop(stop.time(),stop.activity(),0,stop.note(),stop.placeId()),
                new PlannerService.Stop(stop.time(),stop.activity(),stop.estimatedCost(),stop.note(),null))){
            var invalid=new PlannerService.Itinerary("改动", "说明",List.of(changed),List.of(),List.of());
            assertThatThrownBy(()->planner.prepareRepair(original,invalid)).isInstanceOf(IllegalStateException.class);
        }
        var changedCount=new PlannerService.Itinerary("删站", "说明",List.of(),List.of(),List.of());
        assertThatThrownBy(()->planner.prepareRepair(original,changedCount)).isInstanceOf(IllegalStateException.class);
        var candidate=planner.prepareRepair(original,new PlannerService.Itinerary("待发布", "说明",original.itinerary().stops(),List.of(),List.of()));
        var report=PlanQualityService.assess(requirements,candidate,original.evidence());
        var manual=planner.generate(requirements,original.id(),1);
        assertThatThrownBy(()->planner.publishRepair(original,candidate,report)).isInstanceOf(IllegalStateException.class);
        assertThat(planner.latest(original.id())).isEqualTo(manual);assertThat(planner.get(original.id(),1)).isEqualTo(original);
    }

}
