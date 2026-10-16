package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.planner.PlannerService;
import com.yangbin.yangbinaiagent.planner.PlanningEvidence;
import com.yangbin.yangbinaiagent.security.UserContext;
import com.yangbin.yangbinaiagent.tasks.TaskService;
import com.yangbin.yangbinaiagent.tasks.TaskWorkflow;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** State-machine tests use an explicit worker queue: no network, model credentials or timed sleeps. */
class TaskWorkflowTest {
    static final UserContext.User A = new UserContext.User("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "user");
    static final UserContext.User B = new UserContext.User("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "user");
    static final PlannerService.Requirements REQUIREMENTS = new PlannerService.Requirements(
            "北京", 500, "2026-10-20", "14:00", 6, "公园", "公共交通", "");
    static final PlannerService.Itinerary ITINERARY = new PlannerService.Itinerary("测试行程", "两人出行",
            List.of(new PlannerService.Stop("14:00", "公园", 50, "费用估算")), List.of("室内备选"), List.of("确认营业时间"));
    static final PlanningEvidence.Snapshot EVIDENCE = new PlanningEvidence.Snapshot(
            "2026-10-16T00:00:00Z", null, null, List.of(), "disabled", "disabled", List.of("测试离线依据"));
    static final List<Map<String,Object>> SOURCES = List.of(Map.of("source", "private-a.md", "content", "私有参考", "score", 1.0));

    @TempDir Path directory;
    TaskWorkflow workflow;
    QueueExecutor executor;
    TaskService service;
    UserContext.Scope identity;

    @BeforeEach void setup() throws Exception {
        identity = UserContext.open(A);
        workflow = mock(TaskWorkflow.class);
        when(workflow.sources(any())).thenReturn(SOURCES);
        when(workflow.evidence(any())).thenReturn(EVIDENCE);
        when(workflow.draft(any(), anyString(), anyList(), any())).thenReturn(ITINERARY);
        when(workflow.save(anyString(), any(), any(), anyList(), any())).thenAnswer(call ->
                new PlannerService.Plan(call.getArgument(0), 1, "2026-10-16T00:00:00Z",
                        call.getArgument(1), call.getArgument(2), call.getArgument(3), UserContext.current().id(), call.getArgument(4)));
        when(workflow.export(any())).thenAnswer(call -> "/api/files/plan-" + ((PlannerService.Plan)call.getArgument(0)).id() + "-v1.pdf");
        executor = new QueueExecutor();
        service = new TaskService(new ObjectMapper(), workflow, directory.toString(), executor);
    }
    @AfterEach void close() throws Exception { try {service.close();} finally {identity.close();} }
    TaskService.TaskView create() throws Exception {return service.create(UUID.randomUUID().toString(), "安排两人出行并生成 PDF", REQUIREMENTS);}
    TaskService.Step step(TaskService.TaskView task, String id) {return task.steps().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();}

    @Test void completedTaskAndEvidenceSurviveNewServiceInstance() throws Exception {
        String id = create().id(); executor.runNext();
        var done = service.get(id);
        assertThat(done.status()).isEqualTo("SUCCEEDED");
        assertThat(done.pdfUrl()).endsWith(".pdf");
        assertThat(done.steps()).allSatisfy(s -> assertThat(s.status()).isEqualTo("SUCCEEDED"));
        service.close();
        executor = new QueueExecutor();
        service = new TaskService(new ObjectMapper(), workflow, directory.toString(), executor);
        assertThat(service.get(id)).isEqualTo(done);
        assertThat(executor.queued()).isZero();
        verify(workflow, times(1)).draft(any(), anyString(), anyList(), any());
        verify(workflow, times(1)).export(any());
        try (var paths = Files.walk(directory)) {
            assertThat(paths.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).toList()).noneMatch(n -> n.endsWith(".tmp"));
        }
    }

    @Test void queuedTaskBecomesInterruptedAfterRestartAndCanResume() throws Exception {
        String id = create().id();
        assertThat(executor.queued()).isEqualTo(1);
        service.close(); executor = new QueueExecutor();
        service = new TaskService(new ObjectMapper(), workflow, directory.toString(), executor);
        assertThat(service.get(id).status()).isEqualTo("INTERRUPTED");
        assertThat(executor.queued()).isZero();
        service.retry(id); executor.runNext();
        assertThat(service.get(id).status()).isEqualTo("SUCCEEDED");
        verify(workflow, times(1)).draft(any(), anyString(), anyList(), any());
    }

    @Test void retryAfterPdfFailurePreservesSuccessfulModelAndPlanAcrossRestart() throws Exception {
        doThrow(new IllegalStateException("PDF temporarily unavailable"))
                .doReturn("/api/files/recovered.pdf").when(workflow).export(any());
        String id = create().id(); executor.runNext();
        assertThat(service.get(id).status()).isEqualTo("FAILED");
        assertThat(step(service.get(id), "model").status()).isEqualTo("SUCCEEDED");
        service.close(); executor = new QueueExecutor();
        service = new TaskService(new ObjectMapper(), workflow, directory.toString(), executor);
        service.retry(id); executor.runNext();
        assertThat(service.get(id).status()).isEqualTo("SUCCEEDED");
        assertThat(step(service.get(id), "pdf").attempts()).isEqualTo(2);
        verify(workflow, times(1)).sources(any());
        verify(workflow, times(1)).evidence(any());
        verify(workflow, times(1)).draft(any(), anyString(), anyList(), any());
        verify(workflow, times(1)).save(anyString(), any(), any(), anyList(), any());
        verify(workflow, times(2)).export(any());
    }

    @Test void retryAfterEvidenceFailureStartsAtEvidence() throws Exception {
        when(workflow.evidence(any())).thenThrow(new IllegalStateException("provider timeout")).thenReturn(EVIDENCE);
        String id = create().id(); executor.runNext();
        assertThat(service.get(id).status()).isEqualTo("FAILED");
        verify(workflow, never()).draft(any(), anyString(), anyList(), any());
        service.retry(id); executor.runNext();
        assertThat(service.get(id).status()).isEqualTo("SUCCEEDED");
        verify(workflow, times(1)).validate(any());
        verify(workflow, times(1)).sources(any());
        verify(workflow, times(2)).evidence(any());
        verify(workflow, times(1)).draft(any(), anyString(), anyList(), any());
    }

    @Test void unavailableEvidenceSnapshotIsRetainedAndRetryableWithoutStorageError() throws Exception {
        var unavailable=new PlanningEvidence.Snapshot("2026-10-16T00:00:00Z",null,null,List.of(),"unavailable","empty",List.of("天气服务暂不可用"));
        when(workflow.evidence(any())).thenReturn(unavailable,EVIDENCE);
        String id=create().id(); executor.runNext();
        var failed=service.get(id);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.evidence()).isEqualTo(unavailable);
        assertThat(failed.error()).contains("天气").doesNotContain("存储","保存异常");
        assertThat(step(failed,"evidence").status()).isEqualTo("FAILED");
        verify(workflow,never()).draft(any(),anyString(),anyList(),any());
        service.retry(id); executor.runNext();
        assertThat(service.get(id).status()).isEqualTo("SUCCEEDED");
        assertThat(service.get(id).evidence()).isEqualTo(EVIDENCE);
        verify(workflow,times(1)).sources(any());
        verify(workflow,times(2)).evidence(any());
    }

    @Test void queuedCancellationPreventsEveryExternalCall() throws Exception {
        String id = create().id(); service.cancel(id); executor.runNext();
        assertThat(service.get(id).status()).isEqualTo("CANCELLED");
        verify(workflow, never()).sources(any());
        verify(workflow, never()).draft(any(), anyString(), anyList(), any());
        verify(workflow, never()).export(any());
    }

    @Test void cancellationDuringModelStoresNoSubsequentPlanOrPdf() throws Exception {
        AtomicReference<String> id = new AtomicReference<>();
        when(workflow.draft(any(), anyString(), anyList(), any())).thenAnswer(call -> {
            service.cancel(id.get()); return ITINERARY;
        });
        id.set(create().id()); executor.runNext();
        assertThat(service.get(id.get()).status()).isEqualTo("CANCELLED");
        verify(workflow, never()).save(anyString(), any(), any(), anyList(), any());
        verify(workflow, never()).export(any());
    }

    @Test void concurrentCancellationWaitsForCurrentModelAndNeverStartsSave() throws Exception {
        service.close();
        var worker=Executors.newSingleThreadExecutor();
        service=new TaskService(new ObjectMapper(),workflow,directory.toString(),worker);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(workflow.draft(any(),anyString(),anyList(),any())).thenAnswer(call->{
            assertThat(UserContext.current()).isEqualTo(A);
            entered.countDown();
            if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test did not release model");
            return ITINERARY;
        });
        String id=create().id();
        try {
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(service.cancel(id).status()).isEqualTo("CANCELLING");
        } finally {release.countDown();}
        worker.submit(()->{}).get(5,TimeUnit.SECONDS);
        assertThat(service.get(id).status()).isEqualTo("CANCELLED");
        verify(workflow,never()).save(anyString(),any(),any(),anyList(),any());
        verify(workflow,never()).export(any());
    }

    @Test void ownerContextIsCapturedForWorkerAndRestoredAfterExecution() throws Exception {
        when(workflow.sources(any())).thenAnswer(call -> {
            assertThat(UserContext.current()).isEqualTo(A); return SOURCES;
        });
        String id = create().id();
        try (var ignored = UserContext.open(B)) {
            executor.runNext();
            assertThat(UserContext.current()).isEqualTo(B);
            assertThat(service.list()).isEmpty();
            assertThatThrownBy(() -> service.get(id)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.retry(id)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.cancel(id)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(service.get(id).status()).isEqualTo("SUCCEEDED");
    }

    @Test void requestIdIsIdempotentWithinAccountButDoesNotCollideAcrossAccounts() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String a = service.create(requestId, "出行", REQUIREMENTS).id();
        assertThat(service.create(requestId, "出行", REQUIREMENTS).id()).isEqualTo(a);
        assertThat(executor.queued()).isEqualTo(1);
        try (var ignored = UserContext.open(B)) {
            String b = service.create(requestId, "出行", REQUIREMENTS).id();
            assertThat(b).isNotEqualTo(a);
            assertThat(service.list()).hasSize(1);
        }
        executor.runNext(); executor.runNext();
        assertThat(service.list()).hasSize(1);
        verify(workflow, times(2)).draft(any(), anyString(), anyList(), any());
    }

    @Test void concurrentRetrySchedulesOnlyOneWorker() throws Exception {
        doThrow(new IllegalStateException("one failed export"))
                .doReturn("/api/files/recovered.pdf").when(workflow).export(any());
        String id = create().id(); executor.runNext();
        CyclicBarrier ready = new CyclicBarrier(2);
        try (var callers = Executors.newFixedThreadPool(2)) {
            List<Callable<Void>> retries = new ArrayList<>();
            for (int i=0;i<2;i++) retries.add(() -> {try (var ignored = UserContext.open(A)) {
                ready.await(5, TimeUnit.SECONDS);
                try {service.retry(id);} catch (IllegalStateException alreadyScheduled) { /* an explicit conflict is valid */ }
                return null;
            }});
            for (Future<Void> result : callers.invokeAll(retries)) result.get(5, TimeUnit.SECONDS);
        }
        assertThat(executor.queued()).isEqualTo(1); executor.runNext();
        assertThat(service.get(id).status()).isEqualTo("SUCCEEDED");
        verify(workflow, times(2)).export(any());
        verify(workflow, times(1)).draft(any(), anyString(), anyList(), any());
    }

    @Test void rejectsTaskPathTraversalAndInvalidRequestIdentity() {
        assertThatThrownBy(() -> service.get("../escape")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.cancel("../escape")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("../escape", "出行", REQUIREMENTS)).isInstanceOf(IllegalArgumentException.class);
        assertThat(executor.queued()).isZero();
    }

    static final class QueueExecutor extends AbstractExecutorService {
        private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
        private boolean shutdown;
        public void execute(Runnable command) {if (shutdown) throw new RejectedExecutionException(); queue.add(command);}
        void runNext() {Runnable next=queue.poll(); assertThat(next).as("scheduled task").isNotNull(); next.run();}
        int queued() {return queue.size();}
        public void shutdown() {shutdown=true;}
        public List<Runnable> shutdownNow() {shutdown=true; var pending=new ArrayList<>(queue); queue.clear(); return pending;}
        public boolean isShutdown() {return shutdown;}
        public boolean isTerminated() {return shutdown;}
        public boolean awaitTermination(long timeout, TimeUnit unit) {return shutdown;}
    }
}
