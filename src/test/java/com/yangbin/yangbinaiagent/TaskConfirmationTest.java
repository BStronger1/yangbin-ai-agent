package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.planner.PlannerService;
import com.yangbin.yangbinaiagent.security.UserContext;
import com.yangbin.yangbinaiagent.skills.SkillService;
import com.yangbin.yangbinaiagent.tasks.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskConfirmationTest {
    @TempDir Path directory;
    SkillService skills;
    TaskWorkflow workflow;
    TaskService service;
    TaskWorkflowTest.QueueExecutor executor;
    UserContext.Scope identity;
    @BeforeEach void setup() throws Exception {
        identity=UserContext.open(TaskWorkflowTest.A);
        skills=new SkillService(new ObjectMapper(),directory.resolve("skills").toString());
        workflow=mock(TaskWorkflow.class);
        when(workflow.skill(anyString())).thenAnswer(call->skills.resolve(call.getArgument(0)));
        when(workflow.plan(any(),anyString(),any())).thenReturn(TaskPlanningServiceTest.plan("requirements","knowledge","evidence","model","save","pdf"));
        when(workflow.sources(any())).thenReturn(TaskWorkflowTest.SOURCES);
        when(workflow.evidence(any())).thenReturn(TaskWorkflowTest.EVIDENCE);
        when(workflow.draft(any(),anyString(),anyList(),any())).thenReturn(TaskWorkflowTest.ITINERARY);
        when(workflow.save(anyString(),any(),any(),anyList(),any())).thenAnswer(call->new PlannerService.Plan(call.getArgument(0),1,"2026-10-16T00:00:00Z",call.getArgument(1),call.getArgument(2),call.getArgument(3),UserContext.current().id(),call.getArgument(4)));
        when(workflow.export(any())).thenReturn("/api/files/task-confirmation.pdf");
        executor=new TaskWorkflowTest.QueueExecutor();
        service=new TaskService(new ObjectMapper(),workflow,directory.resolve("tasks").toString(),executor);
    }
    @AfterEach void close(){try{service.close();}finally{identity.close();}}
    TaskService.TaskView create() throws Exception {return service.create(UUID.randomUUID().toString(),"北京两人出行",TaskWorkflowTest.REQUIREMENTS,"builtin-city");}
    void restart() throws Exception {
        service.close();executor=new TaskWorkflowTest.QueueExecutor();
        service=new TaskService(new ObjectMapper(),workflow,directory.resolve("tasks").toString(),executor);
    }
    void verifyNoExecution() throws Exception {
        verify(workflow,never()).sources(any());verify(workflow,never()).evidence(any());
        verify(workflow,never()).draft(any(),anyString(),anyList(),any());
        verify(workflow,never()).save(anyString(),any(),any(),anyList(),any());verify(workflow,never()).export(any());
    }
    @Test void planningProducesReviewablePlanAndExecutesNothingUntilConfirmation() throws Exception {
        String id=create().id();executor.runNext();var awaiting=service.get(id);
        assertThat(awaiting.status()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(awaiting.executionPlan().steps()).hasSize(6);assertThat(awaiting.confirmedAt()).isNull();
        verifyNoExecution();assertThat(executor.queued()).isZero();
        service.start(id);assertThat(service.get(id).confirmedAt()).isNotBlank();executor.runNext();
        assertThat(service.get(id).status()).isEqualTo("SUCCEEDED");
        verify(workflow,times(1)).plan(any(),anyString(),any());verify(workflow,times(1)).export(any());
    }
    @Test void concurrentDoubleConfirmationSchedulesOnlyOneExecution() throws Exception {
        String id=create().id();executor.runNext();CyclicBarrier ready=new CyclicBarrier(2);
        try(var callers=Executors.newFixedThreadPool(2)) {
            List<Callable<Void>> attempts=new ArrayList<>();
            for(int i=0;i<2;i++)attempts.add(()->{try(var ignored=UserContext.open(TaskWorkflowTest.A)){
                ready.await(5,TimeUnit.SECONDS);service.start(id);return null;
            }});
            for(var result:callers.invokeAll(attempts))result.get(5,TimeUnit.SECONDS);
        }
        assertThat(executor.queued()).isEqualTo(1);executor.runNext();
        assertThat(service.start(id).status()).isEqualTo("SUCCEEDED");assertThat(executor.queued()).isZero();
        verify(workflow,times(1)).draft(any(),anyString(),anyList(),any());
    }
    @Test void awaitingConfirmationSurvivesRestartAndCannotBeConfirmedByAnotherUser() throws Exception {
        String id=create().id();executor.runNext();var before=service.get(id);restart();
        assertThat(service.get(id)).isEqualTo(before);assertThat(executor.queued()).isZero();
        try(var ignored=UserContext.open(TaskWorkflowTest.B)) {
            assertThatThrownBy(()->service.start(id)).isInstanceOf(IllegalArgumentException.class);
            assertThat(service.list()).isEmpty();
        }
        verifyNoExecution();service.start(id);executor.runNext();assertThat(service.get(id).status()).isEqualTo("SUCCEEDED");
    }
    @Test void confirmedInterruptedTaskResumesWithoutPlanningOrAnotherConfirmation() throws Exception {
        String id=create().id();executor.runNext();service.start(id);String confirmed=service.get(id).confirmedAt();restart();
        assertThat(service.get(id).status()).isEqualTo("INTERRUPTED");
        service.retry(id);executor.runNext();
        assertThat(service.get(id).status()).isEqualTo("SUCCEEDED");assertThat(service.get(id).confirmedAt()).isEqualTo(confirmed);
        verify(workflow,times(1)).plan(any(),anyString(),any());
    }
    @Test void cancellationWhilePlanningNeverResurrectsAwaitingConfirmation() throws Exception {
        AtomicReference<String> id=new AtomicReference<>();
        doAnswer(call->{service.cancel(id.get());return TaskPlanningServiceTest.plan("requirements","evidence","model","save");})
                .when(workflow).plan(any(),anyString(),any());
        id.set(create().id());executor.runNext();
        assertThat(service.get(id.get()).status()).isEqualTo("CANCELLED");
        assertThat(service.get(id.get()).confirmedAt()).isNull();
        assertThatThrownBy(()->service.start(id.get())).isInstanceOf(IllegalStateException.class);
        verifyNoExecution();
    }
    @Test void closeDuringPlanningCannotPublishLatePlanAsAwaitingConfirmation() throws Exception {
        doAnswer(call->{service.close();return TaskPlanningServiceTest.plan("requirements","evidence","model","save");})
                .when(workflow).plan(any(),anyString(),any());
        String id=create().id();executor.runNext();
        assertThat(service.get(id).status()).isEqualTo("INTERRUPTED");
        assertThat(service.get(id).confirmedAt()).isNull();verifyNoExecution();
        executor=new TaskWorkflowTest.QueueExecutor();
        service=new TaskService(new ObjectMapper(),workflow,directory.resolve("tasks").toString(),executor);
        assertThat(service.get(id).status()).isEqualTo("INTERRUPTED");assertThat(executor.queued()).isZero();
    }
    @Test void optionalStepsAreActuallySkippedAndSkillSnapshotSurvivesEditDeletionAndRetry() throws Exception {
        var original=skills.create(new SkillService.Input("轻量技能","只生成方案","FROZEN_SKILL_MARKER 优先室内",false,false,null,true));
        when(workflow.plan(any(),anyString(),any())).thenReturn(TaskPlanningServiceTest.plan("requirements","evidence","model","save"));
        String requestId=UUID.randomUUID().toString();
        String id=service.create(requestId,"北京两人出行",TaskWorkflowTest.REQUIREMENTS,original.id()).id();executor.runNext();
        skills.update(original.id(),new SkillService.Input("新版技能","已修改","NEW_SKILL_MARKER",true,true,1,true));
        var duplicate=service.create(requestId,"北京两人出行",TaskWorkflowTest.REQUIREMENTS,original.id());
        assertThat(duplicate.id()).isEqualTo(id);assertThat(duplicate.skill()).isEqualTo(original);
        skills.delete(original.id(),2);
        assertThat(service.create(requestId,"北京两人出行",TaskWorkflowTest.REQUIREMENTS,original.id()).id()).isEqualTo(id);
        restart();service.start(id);executor.runNext();var done=service.get(id);
        assertThat(done.status()).isEqualTo("SUCCEEDED");assertThat(done.pdfUrl()).isNull();assertThat(done.skill()).isEqualTo(original);
        verify(workflow,never()).sources(any());verify(workflow,never()).export(any());
        var prompt=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(workflow).draft(any(),prompt.capture(),anyList(),any());
        assertThat(prompt.getValue()).contains("FROZEN_SKILL_MARKER").doesNotContain("NEW_SKILL_MARKER");
        verify(workflow,times(1)).plan(any(),anyString(),any());
    }
    @Test void invalidModelPlanNeverBecomesConfirmableOrExecutesUnknownTool() throws Exception {
        when(workflow.plan(any(),anyString(),any())).thenReturn(TaskPlanningServiceTest.plan("requirements","shell","evidence","model","save"));
        String id=create().id();executor.runNext();assertThat(service.get(id).status()).isEqualTo("FAILED");
        assertThatThrownBy(()->service.start(id)).isInstanceOf(IllegalStateException.class);verifyNoExecution();
        when(workflow.plan(any(),anyString(),any())).thenReturn(TaskPlanningServiceTest.plan("requirements","evidence","model","save"));
        service.retry(id);executor.runNext();assertThat(service.get(id).status()).isEqualTo("AWAITING_CONFIRMATION");verifyNoExecution();
    }
    @Test void idempotencyRejectsChangingSkillForSameRequestId() throws Exception {
        String requestId=UUID.randomUUID().toString();
        service.create(requestId,"北京两人出行",TaskWorkflowTest.REQUIREMENTS,"builtin-city");
        assertThatThrownBy(()->service.create(requestId,"北京两人出行",TaskWorkflowTest.REQUIREMENTS,"builtin-rain")).isInstanceOf(IllegalStateException.class);
        assertThat(executor.queued()).isEqualTo(1);
    }
}
