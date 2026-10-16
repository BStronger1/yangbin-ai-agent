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
        Path generated=pdf(result.pdfUrl());
        assertThat(Files.readString(generated, StandardCharsets.ISO_8859_1)).startsWith("%PDF");
        try (var document=new PdfDocument(new PdfReader(generated.toString()))) {
            StringBuilder text=new StringBuilder();
            for(int page=1;page<=document.getNumberOfPages();page++)text.append(PdfTextExtractor.getTextFromPage(document.getPage(page)));
            assertThat(text.toString()).contains("雨天出行计划", "测试公园", "openstreetmap.org/node/42");
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

}
