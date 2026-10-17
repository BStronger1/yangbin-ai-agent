package com.yangbin.yangbinaiagent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.work.*;
import com.yangbin.yangbinaiagent.evaluation.ExecutionProvenance;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class PartnerChatServiceTest {
 @TempDir Path root;
 final ObjectMapper json=new ObjectMapper();
 final ExecutionProvenance provenance=new ExecutionProvenance("m013-test","openai","test-model","");
 ChatModel model;WorkCatalog catalog;WorkService work;PartnerChatService chat;UserContext.Scope scope;
 @BeforeEach void setup()throws Exception{scope=UserContext.open(TaskWorkflowTest.A);model=mock(ChatModel.class);catalog=new WorkCatalog(json);work=new WorkService(json,model,catalog,provenance,root.toString());chat=create();clearInvocations(model);when(model.call(any(Prompt.class))).thenReturn(answer("第一轮答复"));}
 @AfterEach void close(){scope.close();}
 PartnerChatService create()throws Exception{return new PartnerChatService(json,model,catalog,work,provenance,root.toString());}
 static ChatResponse answer(String text){return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));}
 static PartnerChatService.Input input(String id,int revision,String text){return new PartnerChatService.Input(id,UUID.randomUUID().toString(),revision,text);}
 static String id(){return UUID.randomUUID().toString();}
 @Test void twoTurnsCarryExactRoleContextAndVersionedPreferences()throws Exception{
  var profile=chat.saveProfile("office",new PartnerChatService.Profile(0,"简洁中文，行动项表格"));String id=id();
  var first=chat.send("office",input(id,0,"请整理会议要点"));
  chat.saveProfile("office",new PartnerChatService.Profile(profile.version(),"先给结论"));
  when(model.call(any(Prompt.class))).thenAnswer(call->{Prompt p=call.getArgument(0);var m=p.getInstructions();assertThat(m).hasSize(4);assertThat(m.get(0)).isInstanceOf(SystemMessage.class);assertThat(m.get(0).getText()).contains("办公整理伙伴","先给结论").doesNotContain("简洁中文，行动项表格");assertThat(m.get(1).getText()).isEqualTo("请整理会议要点");assertThat(m.get(2)).isInstanceOf(AssistantMessage.class);assertThat(m.get(2).getText()).isEqualTo("第一轮答复");assertThat(m.get(3).getText()).isEqualTo("把行动项分给负责人");return answer("修改后的行动项");});
  var second=chat.send("office",input(id,1,"把行动项分给负责人"));assertThat(second.revision()).isEqualTo(2);assertThat(second.turns().getLast().contextTurnIds()).containsExactly(first.turns().getFirst().id());assertThat(second.turns().getFirst().systemInstructions()).contains("简洁中文，行动项表格");assertThat(second.turns().getLast().profileVersion()).isEqualTo(2);assertThat(second.turns().getLast().attempt().inputHash()).hasSize(64);assertThat(create().get("office",id)).isEqualTo(second);
 }
 @Test void accountPartnerAndNewConversationAreIsolated()throws Exception{
  String id=id();chat.saveProfile("office",new PartnerChatService.Profile(0,"只属于办公伙伴"));chat.send("office",input(id,0,"私有材料"));
  assertThatThrownBy(()->chat.get("marketing",id)).hasMessage("会话不存在");assertThat(chat.list("marketing")).isEmpty();assertThat(chat.profile("marketing").instructions()).isEmpty();
  try(var ignored=UserContext.open(TaskWorkflowTest.B)){assertThat(chat.list("office")).isEmpty();assertThat(chat.profile("office").version()).isZero();assertThatThrownBy(()->chat.get("office",id)).hasMessage("会话不存在");}
  when(model.call(any(Prompt.class))).thenAnswer(call->{Prompt p=call.getArgument(0);assertThat(p.getInstructions()).hasSize(2);assertThat(p.getContents()).doesNotContain("私有材料");return answer("独立回答");});chat.send("office",input(id(),0,"新问题"));
 }
 @Test void duplicateRevisionAndPolicyChecksPreventExtraCalls()throws Exception{
  String id=id();var request=input(id,0,"任务");var saved=chat.send("office",request);work.policy("office",new WorkService.PartnerPolicy("RESTRICTED",false));assertThat(chat.send("office",request)).isEqualTo(saved);
  assertThatThrownBy(()->chat.send("office",new PartnerChatService.Input(id,request.requestId(),0,"不同内容"))).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->chat.send("office",input(id,0,"旧版本"))).hasMessageContaining("新消息");assertThatThrownBy(()->chat.send("office",input(id,1,"限制消息"))).hasMessageContaining("限制");assertThatThrownBy(()->chat.send("office",input(id(),0,"新对话限制"))).hasMessageContaining("限制");assertThat(chat.list("office")).hasSize(1);verify(model,times(1)).call(any(Prompt.class));
 }
 @Test void concurrentDuplicateReturnsReservationAndOtherMessageIsRejected()throws Exception{
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);String id=id();var request=input(id,0,"等待");when(model.call(any(Prompt.class))).thenAnswer(call->{entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return answer("完成");});var executor=Executors.newSingleThreadExecutor();
  try{var future=executor.submit(()->{try(var ignored=UserContext.open(TaskWorkflowTest.A)){return chat.send("office",request);}});assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(chat.send("office",request).turns().getLast().status()).isEqualTo("RUNNING");assertThatThrownBy(()->chat.send("office",input(id,1,"另一条"))).hasMessageContaining("等待");release.countDown();assertThat(future.get(5,TimeUnit.SECONDS).turns().getLast().status()).isEqualTo("SUCCEEDED");verify(model,times(1)).call(any(Prompt.class));}finally{release.countDown();executor.shutdownNow();}
 }
 @Test void failedAndInterruptedTurnsNeverEnterContextOrAutoRetry()throws Exception{
  String id=id();when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("secret-provider-response"));var first=input(id,0,"失败消息");var failed=chat.send("office",first);assertThat(failed.turns().getLast().status()).isEqualTo("FAILED");assertThat(json.writeValueAsString(failed)).doesNotContain("secret-provider-response");assertThat(chat.send("office",first)).isEqualTo(failed);
  when(model.call(any(Prompt.class))).thenThrow(new AssertionError("abrupt shutdown"));var second=input(id,1,"中断消息");assertThatThrownBy(()->chat.send("office",second)).isInstanceOf(AssertionError.class);chat=create();assertThat(chat.get("office",id).turns().getLast().status()).isEqualTo("INTERRUPTED");assertThat(chat.send("office",second).revision()).isEqualTo(2);
  when(model.call(any(Prompt.class))).thenAnswer(call->{Prompt p=call.getArgument(0);assertThat(p.getInstructions()).hasSize(2);return answer("恢复");});assertThat(chat.send("office",input(id,2,"继续")).turns().getLast().contextTurnIds()).isEmpty();verify(model,times(3)).call(any(Prompt.class));
 }
 @Test void boundedContextKeepsWholePairsAndTenMostRecentSuccesses()throws Exception{
  String id=id();for(int i=0;i<12;i++)chat.send("office",input(id,i,"轮次"+i));var c=chat.get("office",id);assertThat(c.turns().getLast().contextTurnIds()).containsExactlyElementsOf(c.turns().subList(1,11).stream().map(PartnerChatService.Turn::id).toList());
  when(model.call(any(Prompt.class))).thenReturn(answer("字".repeat(39000)));String big=id();chat.send("office",input(big,0,"第一大段"));chat.send("office",input(big,1,"第二大段"));var third=chat.send("office",input(big,2,"第三大段"));assertThat(third.turns().getLast().contextTurnIds()).containsExactly(third.turns().get(1).id());
 }
 @Test void profilesAreVersionCheckedClearableAndNarrativeBoundariesExplicit()throws Exception{
  chat.saveProfile("office",new PartnerChatService.Profile(0,"偏好"));assertThatThrownBy(()->chat.saveProfile("office",new PartnerChatService.Profile(0,"冲突"))).hasMessageContaining("刷新");assertThat(chat.saveProfile("office",new PartnerChatService.Profile(1," ")).instructions()).isEmpty();assertThat(create().profile("office").version()).isEqualTo(2);assertThat(chat.instructions("presentation")).contains("不能直接操作项目");assertThatThrownBy(()->chat.profile("../bad")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->chat.saveProfile("office",new PartnerChatService.Profile(2,"x".repeat(2001)))).isInstanceOf(IllegalArgumentException.class);verify(model,never()).call(any(Prompt.class));
 }
}
