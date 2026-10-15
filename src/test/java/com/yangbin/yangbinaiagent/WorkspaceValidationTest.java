package com.yangbin.yangbinaiagent;
import com.yangbin.yangbinaiagent.planner.PlannerService;
import com.yangbin.yangbinaiagent.tools.TracedTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class WorkspaceValidationTest {
 PlannerService.Requirements requirements(){return new PlannerService.Requirements("上海",100,"2026-10-20","14:00",6,"咖啡","步行","");}
 PlannerService.Itinerary itinerary(int cost,String time){return new PlannerService.Itinerary("test","test",List.of(new PlannerService.Stop(time,"coffee",cost,"unverified")),List.of("indoor"),List.of("check price"));}
 @Test void budgetAndTimeAreEnforced(){
  assertThatCode(()->PlannerService.validateItinerary(itinerary(80,"15:00"),requirements())).doesNotThrowAnyException();
  assertThatThrownBy(()->PlannerService.validateItinerary(itinerary(101,"15:00"),requirements())).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->PlannerService.validateItinerary(itinerary(80,"21:00"),requirements())).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->PlannerService.validateItinerary(itinerary(-1,"15:00"),requirements())).isInstanceOf(IllegalStateException.class);
 }
 @Test void crossingMidnightAndInvalidInputAreRejected(){
  assertThatThrownBy(()->PlannerService.validate(new PlannerService.Requirements("上海",100,"2026-10-20","23:00",3,"","步行",""))).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->PlannerService.validate(null)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void tracesReflectRealSuccessAndFailure(){
  var events=new ArrayList<Map<String,Object>>();
  ToolCallback delegate=new ToolCallback(){public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("test").description("test").inputSchema("{}").build();}public String call(String s){if(s.equals("fail"))throw new IllegalStateException("failure");return "actual-result";}};
  var wrapped=TracedTools.wrap(new ToolCallback[]{delegate},events::add)[0];
  assertThat(wrapped.call("{}")).isEqualTo("actual-result");assertThat(events.get(0).get("status")).isEqualTo("running");assertThat(events.get(1).get("result")).isEqualTo("actual-result");
  assertThatThrownBy(()->wrapped.call("fail")).isInstanceOf(IllegalStateException.class);assertThat(events.get(3).get("status")).isEqualTo("failed");
 }
}
