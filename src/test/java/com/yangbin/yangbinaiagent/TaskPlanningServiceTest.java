package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.skills.SkillService;
import com.yangbin.yangbinaiagent.tasks.TaskPlanningService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskPlanningServiceTest {
    static final SkillService.Skill FULL=new SkillService.Skill("builtin-city","城市出行","说明","请引用来源",true,true,1,true,true,"2026-10-16T00:00:00Z");
    static final SkillService.Skill MINIMAL=new SkillService.Skill("minimal","轻量出行","说明","不生成PDF",false,false,1,false,true,"2026-10-16T00:00:00Z");
    static TaskPlanningService.ExecutionPlan plan(String... ids) {
        return new TaskPlanningService.ExecutionPlan("先收集依据，再生成并保存方案",Arrays.stream(ids).map(id->new TaskPlanningService.PlannedStep(id,"执行"+id)).toList());
    }
    @Test void acceptsRequiredStepsAndOnlyAllowedOptionalSteps() {
        assertThatCode(()->TaskPlanningService.validate(plan("requirements","knowledge","evidence","model","save","pdf"),FULL)).doesNotThrowAnyException();
        assertThatCode(()->TaskPlanningService.validate(plan("requirements","evidence","model","save"),MINIMAL)).doesNotThrowAnyException();
    }
    @Test void rejectsUnknownDuplicatedMissingAndOutOfOrderSteps() {
        var invalid=List.of(
                plan("requirements","executeTerminalCommand","evidence","model","save"),
                plan("requirements","evidence","model","save","save"),
                plan("requirements","evidence","save"),
                plan("model","requirements","evidence","save"),
                plan("requirements","evidence","model","pdf","save"));
        for(var item:invalid)assertThatThrownBy(()->TaskPlanningService.validate(item,FULL)).isInstanceOfAny(IllegalArgumentException.class,IllegalStateException.class);
    }
    @Test void skillPermissionsCannotBeEscalatedByPlannerOutput() {
        assertThatThrownBy(()->TaskPlanningService.validate(plan("requirements","knowledge","evidence","model","save"),MINIMAL)).isInstanceOfAny(IllegalArgumentException.class,IllegalStateException.class);
        assertThatThrownBy(()->TaskPlanningService.validate(plan("requirements","evidence","model","save","pdf"),MINIMAL)).isInstanceOfAny(IllegalArgumentException.class,IllegalStateException.class);
    }
    @Test void rejectsNullAndUnboundedPlanExplanations() {
        assertThatThrownBy(()->TaskPlanningService.validate(null,FULL)).isInstanceOfAny(IllegalArgumentException.class,IllegalStateException.class);
        assertThatThrownBy(()->TaskPlanningService.validate(new TaskPlanningService.ExecutionPlan("summary",null),FULL)).isInstanceOfAny(IllegalArgumentException.class,IllegalStateException.class);
        assertThatThrownBy(()->TaskPlanningService.validate(new TaskPlanningService.ExecutionPlan("x".repeat(100000),plan("requirements","evidence","model","save").steps()),FULL)).isInstanceOfAny(IllegalArgumentException.class,IllegalStateException.class);
    }
    @Test void modelPlanContainsSkillConstraintsAndInvalidModelStepsAreRejected() throws Exception {
        var model=mock(ChatModel.class);
        var json=new ObjectMapper();
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(json.writeValueAsString(plan("requirements","evidence","model","save")))))));
        var service=new TaskPlanningService(model,json);
        var result=service.plan(TaskWorkflowTest.REQUIREMENTS,"安排北京出行",MINIMAL);
        assertThat(result.steps()).extracting(TaskPlanningService.PlannedStep::id).containsExactly("requirements","evidence","model","save");
        var prompt=org.mockito.ArgumentCaptor.forClass(Prompt.class); verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getContents()).contains("不生成PDF","北京");
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(json.writeValueAsString(plan("requirements","shell","evidence","model","save")))))));
        assertThatThrownBy(()->service.plan(TaskWorkflowTest.REQUIREMENTS,"安排出行",FULL)).isInstanceOfAny(IllegalArgumentException.class,IllegalStateException.class);
    }
}
