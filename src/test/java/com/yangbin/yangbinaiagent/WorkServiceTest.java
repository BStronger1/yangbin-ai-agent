package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.work.*;
import com.yangbin.yangbinaiagent.evaluation.ExecutionProvenance;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkServiceTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    ChatModel model;
    WorkCatalog catalog;
    WorkService service;
    UserContext.Scope identity;
    @BeforeEach void setup() throws Exception {
        identity = UserContext.open(TaskWorkflowTest.A);
        model = mock(ChatModel.class);
        catalog = new WorkCatalog(json);
        service = createService();
        clearInvocations(model);
    }
    @AfterEach void close() { identity.close(); }
    WorkService createService() throws Exception {
        return new WorkService(json,model,catalog,new ExecutionProvenance("f66db28","openai","test-model",""),root.toString());
    }
    WorkService.Request request() { return new WorkService.Request(UUID.randomUUID().toString(),"campaign-brief","marketing","产品资料：环保笔记本；目标：设计两周内容活动。"); }
    ChatResponse answer(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
    @Test void partnerPolicyPersistsAndBlocksBeforeModelWithoutCrossAccountLeak() throws Exception {
        service.policy("marketing",new WorkService.PartnerPolicy("RESTRICTED",false));
        service=createService();
        assertThat(service.policy("marketing").enabled()).isFalse();
        assertThatThrownBy(() -> service.execute(request())).isInstanceOf(IllegalStateException.class).hasMessageContaining("限制");
        assertThat(service.list()).isEmpty(); verify(model,never()).call(any(Prompt.class));
        try(var ignored=UserContext.open(TaskWorkflowTest.B)) {
            assertThat(service.policy("marketing").enabled()).isTrue();
            assertThat(service.summoned()).isEmpty();
        }
        service.policy("marketing",new WorkService.PartnerPolicy("ALLOW",false));
        assertThat(service.policy("marketing").enabled()).isTrue();
        assertThatThrownBy(() -> service.policy("marketing",new WorkService.PartnerPolicy("INVALID",true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.policy("../invalid")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void summonedPartnersIncludeSavedLibraryAndPreviousTasks() throws Exception {
        service.select("partner","office",true);
        when(model.call(any(Prompt.class))).thenReturn(answer("测试任务结果"));
        var completed=service.execute(request());
        assertThat(createService().summoned()).containsExactlyInAnyOrder("office","marketing");
        service.policy("marketing",new WorkService.PartnerPolicy("RESTRICTED",false));
        assertThat(service.execute(new WorkService.Request(completed.id(),"campaign-brief","marketing",completed.prompt()))).isEqualTo(completed);
        verify(model,times(1)).call(any(Prompt.class));
        assertThat(service.partnerDetail("marketing").get("instructions").toString()).contains("没有联网",catalog.skill("campaign-brief").instructions());
    }
    @Test void catalogRoutesTravelSeparatelyAndEveryPartnerUsesRealTextSkill() {
        assertThat(catalog.all().skills()).hasSize(18);
        assertThat(catalog.all().skills().stream().filter(s -> s.kind().equals("TEXT")).toList()).hasSize(16);
        assertThat(catalog.all().partners()).hasSize(8);
        assertThat(catalog.all().partners()).allMatch(p -> catalog.skill(p.skillId()).kind().equals("TEXT"));
        assertThat(catalog.all().skills().stream().filter(s -> s.kind().equals("TEXT")).map(WorkCatalog.Skill::instructions).distinct().count()).isEqualTo(16);
    }
    @Test void legacyPartnerSnapshotsRemainReadableWithWorkspacePartner() throws Exception {
        var legacy = json.readValue("{\"id\":\"presentation\",\"name\":\"演示策划伙伴\",\"skillId\":\"presentation-outline\",\"description\":\"旧任务\",\"category\":\"办公提效\",\"color\":\"violet\",\"initial\":\"演\",\"tags\":[]}",WorkCatalog.Partner.class);
        assertThat(legacy.workspace()).isNull();
        assertThat(legacy.name()).isEqualTo("演示策划伙伴");
        assertThat(catalog.partner(legacy.id()).workspace()).isEqualTo("narrative-ppt");
        service.select("partner",legacy.id(),true);
        assertThat(createService().library().partners()).containsExactly("presentation");
    }
    @Test void libraryPersistsIdempotentlyAndCannotCrossAccounts() throws Exception {
        service.select("skill","campaign-brief",true); service.select("skill","campaign-brief",true);
        service.select("partner","marketing",true);
        service=createService();
        assertThat(service.library().skills()).containsExactly("campaign-brief");
        assertThat(service.library().partners()).containsExactly("marketing");
        try(var ignored=UserContext.open(TaskWorkflowTest.B)) {
            assertThat(service.library().skills()).isEmpty(); assertThat(service.library().partners()).isEmpty();
            service.select("skill","ad-copy",true);
        }
        service.select("skill","campaign-brief",false);
        assertThat(service.library().skills()).isEmpty();
        assertThatThrownBy(() -> service.select("skill","nonexistent",true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.library().partners()).containsExactly("marketing");
    }
    @Test void generationReservesSnapshotBeforeModelAndPersistsExactOutput() throws Exception {
        var request=request();
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            var saved=service.get(request.requestId());
            assertThat(saved.status()).isEqualTo("RUNNING");
            assertThat(saved.executionAttempt().inputHash()).hasSize(64);
            assertThat(saved.executionAttempt().configuredModel()).isEqualTo("test-model");
            assertThat(saved.skill().instructions()).isEqualTo(catalog.skill("campaign-brief").instructions());
            Prompt prompt=call.getArgument(0);
            assertThat(prompt.getContents()).contains("营销","不宣称效果提升",request.prompt(),"没有联网");
            assertThat(prompt.getContents()).doesNotContain("候选地点");
            return answer("# 活动 Brief\n\n目标与假设\n\n<script>alert('data')</script>");
        });
        var run=service.execute(request);
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(service.get(run.id())).isEqualTo(run);
        assertThat(run.result()).contains("<script>"); // Stored as text; UI must escape it.
        assertThat(service.list()).hasSize(1);
        assertThat(service.execute(request)).isEqualTo(run);
        verify(model,times(1)).call(any(Prompt.class));
        service=createService();
        assertThat(service.get(run.id())).isEqualTo(run);
    }
    @Test void duplicateInFlightRequestReusesReservationAndOtherWorkIsBounded() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var request=request();
        when(model.call(any(Prompt.class))).thenAnswer(call -> { entered.countDown(); assertThat(release.await(5,TimeUnit.SECONDS)).isTrue(); return answer("完成"); });
        var executor=Executors.newSingleThreadExecutor();
        try {
            var future=executor.submit(() -> { try(var ignored=UserContext.open(TaskWorkflowTest.A)) { return service.execute(request); } });
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(service.execute(request).status()).isEqualTo("RUNNING");
            assertThatThrownBy(() -> service.execute(request())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.execute(new WorkService.Request(request.requestId(),"campaign-brief","marketing","不同材料"))).isInstanceOf(IllegalStateException.class);
            release.countDown(); assertThat(future.get(5,TimeUnit.SECONDS).status()).isEqualTo("SUCCEEDED");
            verify(model,times(1)).call(any(Prompt.class));
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void providerFailureIsSanitizedAndSameRequestNeverAutomaticallyRetries() throws Exception {
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("secret-provider-body"));
        var request=request(); var failed=service.execute(request);
        assertThat(failed.status()).isEqualTo("FAILED"); assertThat(failed.result()).isNull();
        assertThat(json.writeValueAsString(failed)).doesNotContain("secret-provider-body");
        assertThat(service.execute(request)).isEqualTo(failed);
        verify(model,times(1)).call(any(Prompt.class));
    }
    @Test void lostResponseBecomesInterruptedOnServiceReconstructionWithoutRetry() throws Exception {
        when(model.call(any(Prompt.class))).thenThrow(new AssertionError("simulate abrupt loss"));
        var request=request();
        assertThatThrownBy(() -> service.execute(request)).isInstanceOf(AssertionError.class);
        assertThat(service.get(request.requestId()).status()).isEqualTo("RUNNING");
        service=createService();
        assertThat(service.get(request.requestId()).status()).isEqualTo("INTERRUPTED");
        assertThat(service.execute(request).status()).isEqualTo("INTERRUPTED");
        verify(model,times(1)).call(any(Prompt.class));
    }
    @Test void ownershipAndInvalidRoutesRejectBeforeCallingModel() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(answer("完成"));
        var request=request(); service.execute(request);
        try(var ignored=UserContext.open(TaskWorkflowTest.B)) {
            assertThat(service.list()).isEmpty();
            assertThatThrownBy(() -> service.get(request.requestId())).isInstanceOf(IllegalArgumentException.class).hasMessage("任务不存在");
        }
        for(var bad:List.of(new WorkService.Request("../escape","campaign-brief",null,"任务"),
            new WorkService.Request(UUID.randomUUID().toString(),"builtin-city",null,"任务"),
            new WorkService.Request(UUID.randomUUID().toString(),"campaign-brief","presentation","任务"),
            new WorkService.Request(UUID.randomUUID().toString(),"unknown",null,"任务"),
            new WorkService.Request(UUID.randomUUID().toString(),"campaign-brief",null," "),
            new WorkService.Request(UUID.randomUUID().toString(),"campaign-brief",null,"x".repeat(12001)))) {
            assertThatThrownBy(() -> service.execute(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        verify(model,times(1)).call(any(Prompt.class));
    }
}
