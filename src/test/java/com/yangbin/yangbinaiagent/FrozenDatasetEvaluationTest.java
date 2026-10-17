package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yangbin.yangbinaiagent.planner.*;
import com.yangbin.yangbinaiagent.rag.KnowledgeService;
import com.yangbin.yangbinaiagent.security.UserContext;
import com.yangbin.yangbinaiagent.skills.SkillService;
import com.yangbin.yangbinaiagent.tasks.*;
import com.yangbin.yangbinaiagent.controller.FileController;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Frozen dataset runner. Real persistence, rules, state machines and PDFs; external calls are fixtures. */
class FrozenDatasetEvaluationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Path DATA = Path.of("evaluation/datasets/travel-eval-v1");
    static final String PIN = "a1c7eda476c3bfedd5cea07bc1851ae920d91883dd39a2995c2b8b00ceef4974";
    static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    static void save(Path path,Object value) throws Exception {
        Files.createDirectories(path.getParent()); JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),value);
    }

    static ObjectNode codeSnapshot() throws Exception {
        ObjectNode snapshot=JSON.createObjectNode();
        var git=new ProcessBuilder("git","rev-parse","HEAD").start();
        String head=new String(git.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
        snapshot.put("head",git.waitFor()==0?head:"unknown");
        ObjectNode hashes=snapshot.putObject("fileHashes");
        for(String dir:List.of("src/main/java","src/test/java","tools/evaluation")) {
            try(var paths=Files.walk(Path.of(dir))) {
                for(Path p:paths.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".java")||p.toString().endsWith(".py")).sorted().toList())
                    hashes.put(p.toString().replace('\\','/'),hash(p));
            }
        }
        hashes.put("pom.xml",hash(Path.of("pom.xml")));
        snapshot.put("interpretation","Working-file fingerprints identify this run; HEAD alone does not describe uncommitted code.");
        return snapshot;
    }

    @Test void executeFrozenOfflineCasesAndPreserveAllOutcomes() throws Exception {
        JsonNode manifest=JSON.readTree(DATA.resolve("manifest.json").toFile());
        assertThat(manifest.path("datasetHash").asText()).isEqualTo(PIN);
        for(var it=manifest.path("files").fields();it.hasNext();) {
            var f=it.next();assertThat(hash(DATA.resolve(f.getKey()))).isEqualTo(f.getValue().asText());
        }
        JsonNode cases=JSON.readTree(DATA.resolve("cases.json").toFile()).path("cases");
        String runId=UUID.randomUUID().toString();
        Path root=Path.of("target/evaluation").resolve(runId);Files.createDirectories(root);
        ObjectNode report=JSON.createObjectNode();
        report.put("schemaVersion","travel-offline-observations-v1").put("runId",runId)
            .put("datasetHash",PIN).put("startedAt",Instant.now().toString())
            .put("mode","offline_fixtures").put("upstreamModelRequests",0)
            .put("externalSourceRequests",0).putNull("tokens").putNull("cost");
        report.set("codeSnapshot",codeSnapshot());
        ArrayNode rows=report.putArray("cases");
        List<String> failures=new ArrayList<>();
        for(JsonNode c:cases) {
            ObjectNode row=rows.addObject();
            for(String k:List.of("caseId","caseHash","inputHash","family"))row.set(k,c.get(k));
            String id=c.path("caseId").asText();long started=System.nanoTime();
            row.set("frozenInput",c.get("input"));
            try {
                switch(c.path("family").asText()) {
                    case "grounding_replay" -> replay(c,row);
                    case "workflow_fault" -> {try(var harness=new Harness(root.resolve(id),c,cases,row)){harness.run();}}
                    case "live_task" -> row.put("status","NOT_RUN").put("reason","Requires real sources/model and runtime date binding; excluded from offline denominators");
                    default -> throw new IllegalArgumentException("Unsupported family");
                }
            } catch(Exception|AssertionError failure) {
                row.put("status","HARNESS_FAILED").put("error",failure.toString());failures.add(id);
            } finally {
                row.put("elapsedMs",(System.nanoTime()-started)/1_000_000);
                save(root.resolve("observations.json"),report);
            }
        }
        report.put("sourceStableDuringRun",report.get("codeSnapshot").equals(codeSnapshot()));
        report.put("finishedAt",Instant.now().toString());
        save(root.resolve("observations.json"),report);
        System.out.println("Frozen evaluation observations: "+root.resolve("observations.json").toAbsolutePath());
        assertThat(report.path("sourceStableDuringRun").asBoolean()).as("Sources unchanged during run").isTrue();
        assertThat(failures).as("Failed cases retained in observations.json").isEmpty();
    }
    void replay(JsonNode c,ObjectNode row) throws Exception {
        JsonNode input=c.path("input");
        var r=JSON.treeToValue(input.path("requirements"),PlannerService.Requirements.class);
        var i=JSON.treeToValue(input.path("itinerary"),PlannerService.Itinerary.class);
        var e=JSON.treeToValue(input.path("evidence"),PlanningEvidence.Snapshot.class);
        ObjectNode structural=row.putObject("structure");
        try {
            PlannerService.validate(r);PlannerService.validateItinerary(i,r);PlannerService.validatePlaces(i,e);
            structural.put("status","PASS");
        } catch(IllegalArgumentException|IllegalStateException rejected) {
            structural.put("status","FAIL").put("reason",rejected.getMessage());
        }
        row.set("quality",JSON.valueToTree(PlanQualityService.assess(r,i,e)));
        row.set("claimReview",JSON.valueToTree(SourceClaimReview.assess(r,i,e)));
        row.put("status","OBSERVED").put("logicalModelAttempts",0);
    }

    static final class SimulatedProcessLoss extends Error {}

    static class Harness implements AutoCloseable {
        final Path root;
        final JsonNode c;
        final ObjectNode row;
        final PlannerService.Requirements req;
        final PlanningEvidence.Snapshot evidence;
        final PlannerService.Itinerary output;
        final ChatModel model=mock(ChatModel.class);
        final PlanningEvidence upstream=mock(PlanningEvidence.class);
        final TaskPlanningService planning=mock(TaskPlanningService.class);
        final PlannerService planner;
        final TaskWorkflow workflow;
        final PlanRepairService repairs;
        final UserContext.Scope identity=UserContext.open(TaskWorkflowTest.A);
        final AtomicInteger modelCalls=new AtomicInteger();
        final List<Path> generatedPdfs=new ArrayList<>();
        TaskWorkflowTest.QueueExecutor executor=new TaskWorkflowTest.QueueExecutor();
        TaskService service;
        String taskId;
        @SuppressWarnings("unchecked")
        Harness(Path root,JsonNode c,JsonNode cases,ObjectNode row) throws Exception {
            this.root=root;this.c=c;this.row=row;
            req=JSON.treeToValue(c.at("/input/requirements"),PlannerService.Requirements.class);
            evidence=JSON.treeToValue(c.at("/input/evidence"),PlanningEvidence.Snapshot.class);
            String id=c.path("caseId").asText();
            String stubCase=Set.of("F05","F06","F07").contains(id)?"G12":"G01";
            JsonNode stub=null;
            for(JsonNode entry:cases)if(entry.path("caseId").asText().equals(stubCase))stub=entry.at("/input/itinerary");
            output=JSON.treeToValue(stub,PlannerService.Itinerary.class);
            row.set("resolvedInput",c.path("input").deepCopy());
            ((ObjectNode)row.get("resolvedInput")).set("stubItinerary",stub);
            ((ObjectNode)row.get("resolvedInput")).put("stubCaseId",stubCase);
            row.put("executionBoundary","real Java services/files/PDF; mocked ChatModel, source fetch and planning model; no HTTP/auth-filter exercise");
            when(upstream.fetch(anyString(),anyString())).thenReturn(evidence);
            when(model.call(any(Prompt.class))).thenAnswer(call->{
                modelCalls.incrementAndGet();
                return new ChatResponse(List.of(new Generation(new AssistantMessage(JSON.writeValueAsString(output)))));
            });
            when(planning.plan(any(),anyString(),any())).thenReturn(TaskPlanningServiceTest.plan("requirements","knowledge","evidence","model","save","pdf"));
            planner=new PlannerService(model,upstream,JSON,mock(ObjectProvider.class),root.resolve("plans").toString());
            var skills=new SkillService(JSON,root.resolve("skills").toString());
            workflow=spy(new TaskWorkflow(planner,upstream,skills,planning));
            // Capture every real generated file, copy to run artifacts, then delete only this run's known file.
            doAnswer(call->{
                String url=(String)call.callRealMethod();
                Path file=Path.of("tmp/pdf",TaskWorkflowTest.A.id(),url.substring(url.lastIndexOf('/')+1));
                generatedPdfs.add(file);Files.createDirectories(root.resolve("pdf"));
                Files.copy(file,root.resolve("pdf").resolve(file.getFileName()),StandardCopyOption.REPLACE_EXISTING);
                return url;
            }).when(workflow).export(any());
            service=new TaskService(JSON,workflow,root.resolve("tasks").toString(),executor);
            repairs=new PlanRepairService(JSON,planner,root.resolve("repairs").toString());
        }
        String create(boolean skill) throws Exception {
            taskId=service.create(UUID.randomUUID().toString(),c.at("/input/prompt").asText(),req,skill?"builtin-city":null).id();
            executor.runNext();return taskId;
        }
        void snapshot(String name) throws Exception {
            if(taskId!=null)save(root.resolve(name+".json"),service.get(taskId));
        }
        int attempts(String step) {
            return service.get(taskId).steps().stream().filter(s->s.id().equals(step)).findFirst().orElseThrow().attempts();
        }
        void restart(Path tasks) throws Exception {
            service.close();executor=new TaskWorkflowTest.QueueExecutor();
            service=new TaskService(JSON,workflow,tasks.toString(),executor);
        }
        void check(int index,String evidenceText,org.assertj.core.api.ThrowableAssert.ThrowingCallable assertion) throws Throwable {
            assertion.call();
            ArrayNode checks=row.has("assertions")?(ArrayNode)row.get("assertions"):row.putArray("assertions");
            ObjectNode a=checks.addObject();a.setAll((ObjectNode)c.path("assertions").get(index));
            a.put("status","PASS").put("observedEvidence",evidenceText);
        }
        void run() throws Exception {
            // Each adapter is explicitly bound to the immutable hook, not to a generic test pass count.
            String hook=c.at("/input/fault/hook").asText();
            try {
                switch(hook) {
                    case "evidence_unavailable" -> {
                        var bad=(ObjectNode)c.at("/input/evidence").deepCopy();
                        bad.put("weatherStatus",c.at("/input/fault/parameters/weatherStatus").asText());
                        bad.put("placesStatus",c.at("/input/fault/parameters/placesStatus").asText());bad.putNull("weather");
                        row.set("injectedEvidence",bad);
                        when(upstream.fetch(anyString(),anyString())).thenReturn(JSON.treeToValue(bad,PlanningEvidence.Snapshot.class),evidence);
                        create(false);snapshot("failed-before-retry");
                        check(0,"failed-before-retry.json and tasks/<id>.json",()->{
                            assertThat(service.get(taskId).status()).isEqualTo("FAILED");assertThat(modelCalls.get()).isZero();
                        });
                        service.retry(taskId);executor.runNext();snapshot("after-retry");
                        check(1,"after-retry.json: evidence attempts=2; requirements/knowledge=1",()->{
                            assertThat(service.get(taskId).status()).isEqualTo("SUCCEEDED");
                            assertThat(attempts("requirements")).isEqualTo(1);assertThat(attempts("knowledge")).isEqualTo(1);
                            assertThat(attempts("evidence")).isEqualTo(2);assertThat(modelCalls.get()).isEqualTo(1);
                        });
                    }
                    case "pdf_failure_once" -> {
                        AtomicInteger exports=new AtomicInteger();
                        doAnswer(call->{
                            if(exports.incrementAndGet()==c.at("/input/fault/parameters/failOnAttempt").asInt())throw new IllegalStateException("Injected PDF failure");
                            String url=(String)call.callRealMethod();
                            Path f=Path.of("tmp/pdf",TaskWorkflowTest.A.id(),url.substring(url.lastIndexOf('/')+1));
                            generatedPdfs.add(f);Files.createDirectories(root.resolve("pdf"));Files.copy(f,root.resolve("pdf").resolve(f.getFileName()));return url;
                        }).when(workflow).export(any());
                        create(false);snapshot("failed-before-retry");
                        var original=planner.get(taskId,1);String before=hash(root.resolve("plans/"+taskId+"-1.json"));
                        check(0,"failed-before-retry.json contains saved plan and failed PDF step",()->{
                            assertThat(service.get(taskId).status()).isEqualTo("FAILED");assertThat(service.get(taskId).planId()).isEqualTo(original.id());
                        });
                        service.retry(taskId);executor.runNext();snapshot("after-retry");
                        check(1,"plan file SHA256 unchanged; one draft; PDF attempt=2",()->{
                            assertThat(service.get(taskId).status()).isEqualTo("SUCCEEDED");assertThat(modelCalls.get()).isEqualTo(1);
                            assertThat(attempts("pdf")).isEqualTo(2);assertThat(hash(root.resolve("plans/"+taskId+"-1.json"))).isEqualTo(before);
                        });
                        row.put("originalPlanHash",before);
                    }
                    case "restart_model_inflight" -> {
                        Path interrupted=root.resolve("restart-tasks");Files.createDirectories(interrupted);
                        doAnswer(call->{
                            // Copy only the durable checkpoint while the model boundary is RUNNING, before any returned draft.
                            Files.copy(root.resolve("tasks/"+taskId+".json"),interrupted.resolve(taskId+".json"),StandardCopyOption.REPLACE_EXISTING);
                            call.callRealMethod();
                            throw new SimulatedProcessLoss();
                        }).when(workflow).draft(any(),anyString(),anyList(),any());
                        taskId=service.create(UUID.randomUUID().toString(),c.at("/input/prompt").asText(),req).id();
                        assertThatThrownBy(executor::runNext).isInstanceOf(SimulatedProcessLoss.class);
                        assertThat(root.resolve("plans/"+taskId+"-1.json")).doesNotExist();
                        JsonNode checkpoint=JSON.readTree(interrupted.resolve(taskId+".json").toFile());
                        save(root.resolve("inflight-checkpoint.json"),checkpoint);
                        assertThat(checkpoint.path("status").asText()).isEqualTo("RUNNING");
                        assertThat(checkpoint.path("itinerary").isNull()).isTrue();
                        // Error bypasses the normal Exception handler; only the copied durable state is restored.
                        // This is a process-loss simulation, not a claim of actually killing a JVM.
                        restart(interrupted);snapshot("after-restart");
                        check(0,"inflight-checkpoint.json / after-restart.json; queue empty",()->{
                            assertThat(service.get(taskId).status()).isEqualTo("INTERRUPTED");assertThat(executor.queued()).isZero();
                        });
                        doCallRealMethod().when(workflow).draft(any(),anyString(),anyList(),any());
                        service.retry(taskId);executor.runNext();snapshot("after-retry");
                        check(1,"two model attempts in restarted durable state; no exactly-once billing claim",()->{
                            assertThat(service.get(taskId).status()).isEqualTo("SUCCEEDED");
                            assertThat(attempts("model")).isEqualTo(2);assertThat(modelCalls.get()).isEqualTo(2);
                            JsonNode state=JSON.readTree(interrupted.resolve(taskId+".json").toFile());
                            long count=0;for(JsonNode a:state.path("executionAttempts"))if(a.path("stepId").asText().equals("model"))count++;
                            assertThat(count).isEqualTo(2);
                        });
                        row.put("restartMode","copied_inflight_checkpoint_service_reconstruction_not_OS_kill");
                        row.put("possibleRepeatedBilling",true);
                    }
                    case "duplicate_confirmation" -> {
                        create(true);var before=service.get(taskId);snapshot("awaiting");
                        int n=c.at("/input/fault/parameters/concurrentConfirmations").asInt();
                        CyclicBarrier ready=new CyclicBarrier(n);
                        try(var callers=Executors.newFixedThreadPool(n)) {
                            List<Callable<Void>> actions=new ArrayList<>();
                            for(int j=0;j<n;j++)actions.add(()->{try(var ignored=UserContext.open(TaskWorkflowTest.A)){
                                ready.await(5,TimeUnit.SECONDS);service.start(taskId);return null;
                            }});
                            for(var f:callers.invokeAll(actions,10,TimeUnit.SECONDS))f.get(5,TimeUnit.SECONDS);
                        }
                        row.put("queuedAfterConfirmations",executor.queued());
                        check(0,"awaiting.json / after-confirmations.json preserve same execution plan and skill",()->{
                            assertThat(service.get(taskId).executionPlan()).isEqualTo(before.executionPlan());
                            assertThat(service.get(taskId).skill()).isEqualTo(before.skill());
                            verify(planning,times(1)).plan(any(),anyString(),any());
                        });
                        assertThat(executor.queued()).isEqualTo(1);executor.runNext();snapshot("after-confirmations");
                        check(1,"one queued execution, one saved plan, one draft call",()->{
                            assertThat(service.get(taskId).status()).isEqualTo("SUCCEEDED");assertThat(modelCalls.get()).isEqualTo(1);
                            assertThat(planner.versions(taskId)).hasSize(1);assertThat(executor.queued()).isZero();
                        });
                    }
                    case "stale_repair_version" -> {
                        var first=planner.generate(req,null,null);
                        var second=planner.generate(req,first.id(),1);
                        int beforeCalls=modelCalls.get();String before=hash(root.resolve("plans/"+first.id()+"-2.json"));
                        Throwable denied=catchThrowable(()->repairs.repair(first.id(),c.at("/input/fault/parameters/expectedVersion").asInt()));
                        row.put("rejection",denied==null?"NONE":denied.getMessage());
                        check(0,"plans contain v1/v2; v2 fingerprint unchanged",()->{
                            assertThat(second.version()).isEqualTo(c.at("/input/fault/parameters/latestVersion").asInt());
                            assertThat(denied).isInstanceOf(IllegalStateException.class);assertThat(planner.latest(first.id())).isEqualTo(second);
                            assertThat(hash(root.resolve("plans/"+first.id()+"-2.json"))).isEqualTo(before);
                        });
                        check(1,"mock model call delta=0; no repair ledger created",()->{
                            assertThat(modelCalls.get()).isEqualTo(beforeCalls);assertThat(root.resolve("repairs/"+first.id()+".json")).doesNotExist();
                        });
                        row.put("repairModelCallDelta",modelCalls.get()-beforeCalls);
                    }
                    case "repair_no_improvement" -> {
                        var original=planner.generate(req,null,null);int beforeCalls=modelCalls.get();
                        assertThat(c.at("/input/fault/parameters/candidate").asText()).isEqualTo("same_as_original");
                        var first=repairs.repair(original.id(),1);save(root.resolve("repair-first.json"),first);
                        check(0,"repair-first.json rejected; plans directory has only original v1",()->{
                            assertThat(first.status()).isEqualTo("REJECTED");assertThat(planner.versions(original.id())).hasSize(1);
                            assertThat(planner.get(original.id(),1)).isEqualTo(original);
                        });
                        var repeated=repairs.repair(original.id(),1);save(root.resolve("repair-repeat.json"),repeated);
                        check(1,"repair-repeat.json retains attempts=1; model call delta=1",()->{
                            assertThat(repeated).isEqualTo(first);assertThat(repeated.attempts()).isEqualTo(1);
                            assertThat(modelCalls.get()-beforeCalls).isEqualTo(1);
                            assertThat(JSON.readTree(root.resolve("repairs/"+original.id()+".json").toFile()).path("executionAttempt").isObject()).isTrue();
                        });
                        row.put("repairModelCallDelta",modelCalls.get()-beforeCalls);
                    }
                    case "cross_owner_read" -> {
                        create(false);assertThat(service.get(taskId).status()).isEqualTo("SUCCEEDED");
                        repairs.repair(taskId,1);snapshot("owner-a");
                        String name=service.get(taskId).pdfUrl().replace("/api/files/","");
                        assertThat(new FileController().download(name).getStatusCode().value()).isEqualTo(200);
                        assertThat(repairs.get(taskId).status()).isEqualTo("REJECTED");
                        try(var ignored=UserContext.open(TaskWorkflowTest.B)) {
                            List<Throwable> denials=List.of(
                                catchThrowable(()->service.get(taskId)),catchThrowable(()->planner.get(taskId,1)),catchThrowable(()->repairs.get(taskId)));
                            var response=new FileController().download(name);
                            row.set("denialMessages",JSON.valueToTree(denials.stream().map(Throwable::getMessage).toList()));
                            row.put("ownerAPdfStatus",200).put("ownerBPdfStatus",response.getStatusCode().value());
                            check(0,"owner-a.json proves objects exist; denialMessages and PDF404 show owner B denied",()->{
                                assertThat(denials).allMatch(e->e instanceof IllegalArgumentException);
                                assertThat(response.getStatusCode().value()).isEqualTo(404);
                            });
                            check(1,"generic denial messages; no PDF body; owner B task/plan lists empty",()->{
                                assertThat(response.getBody()).isNull();assertThat(service.list()).isEmpty();assertThat(planner.versions(taskId)).isEmpty();
                                for(var e:denials)assertThat(e.getMessage()).doesNotContain(req.city(),output.title(),TaskWorkflowTest.A.id());
                            });
                        }
                    }
                    case "cancel_before_confirmation" -> {
                        create(true);assertThat(service.get(taskId).status()).isEqualTo("AWAITING_CONFIRMATION");snapshot("awaiting");
                        service.cancel(taskId);restart(root.resolve("tasks"));snapshot("after-cancel-reload");
                        check(0,"after-cancel-reload.json CANCELLED; start throws IllegalStateException",()->{
                            assertThat(service.get(taskId).status()).isEqualTo("CANCELLED");
                            assertThatThrownBy(()->service.start(taskId)).isInstanceOf(IllegalStateException.class);
                        });
                        check(1,"no evidence/draft/save/export or source calls after planning",()->{
                            assertThat(executor.queued()).isZero();assertThat(modelCalls.get()).isZero();
                            verify(workflow,never()).sources(any());verify(workflow,never()).evidence(any());
                            verify(workflow,never()).save(anyString(),any(),any(),anyList(),any());verify(workflow,never()).export(any());
                        });
                    }
                    default -> throw new IllegalArgumentException("Unimplemented hook: "+hook);
                }
                assertThat(row.path("assertions").size()).isEqualTo(c.path("assertions").size());
                row.put("status","PASS");
            } catch(Throwable failure) {
                if(failure instanceof Exception e)throw e;
                if(failure instanceof AssertionError e)throw e;
                throw new RuntimeException(failure);
            } finally {
                row.put("mockChatModelCalls",modelCalls.get());
                row.put("mockPlanningCalls",mockingDetails(planning).getInvocations().size());
                row.put("artifacts",root.getFileName().toString());
                if(taskId!=null)row.set("finalTask",JSON.valueToTree(service.get(taskId)));
            }
        }
        public void close() throws Exception {
            try {service.close();repairs.close();
                for(Path p:generatedPdfs)Files.deleteIfExists(p);
            } finally {identity.close();}
        }
    }
}
