package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.controller.AssistantController;
import com.yangbin.yangbinaiagent.work.*;
import com.yangbin.yangbinaiagent.evaluation.ExecutionProvenance;
import com.yangbin.yangbinaiagent.governance.ModelGovernance;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantWorkspaceTest {
 @TempDir Path root;
 final ObjectMapper json=new ObjectMapper();
 final ExecutionProvenance provenance=new ExecutionProvenance("m026-fixture","mock","fixture","");
 ChatModel model;WorkCatalog catalog;WorkService work;PartnerResources resources;PartnerChatService service;AssistantController api;UserContext.Scope scope;
 @BeforeEach void setup()throws Exception {scope=UserContext.open(TaskWorkflowTest.A);model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(PartnerChatServiceTest.answer("合成答复"));catalog=new WorkCatalog(json);work=new WorkService(json,model,catalog,provenance,root.toString());resources=new PartnerResources(json,catalog,root.toString());service=create(model);api=new AssistantController(service);clearInvocations(model);}
 @AfterEach void close(){service.close();scope.close();}
 PartnerChatService create(ChatModel m)throws Exception{return new PartnerChatService(json,m,catalog,work,provenance,root.toString(),resources);}
 String id(){return UUID.randomUUID().toString();}
 PartnerChatService.Brief brief(){return new PartnerChatService.Brief("整理给定材料","可审阅报告","仅使用提供的材料");}
 PartnerChatService.Input request(String conversation,String mode){return new PartnerChatService.Input(conversation,id(),0,"合成任务","auto",List.of(),null,mode,mode.equals("work")?brief():null,mode.equals("work"));}
 PartnerChatService.Conversation await(String id)throws Exception {for(int i=0;i<500;i++){var c=service.get("office",id);if(!c.turns().getLast().status().equals("RUNNING"))return c;Thread.sleep(10);}throw new AssertionError("Job did not settle");}
 @Test void chatRespondsDirectlyWithoutArtifactsOrWorkBrief()throws Exception {
  var input=request(id(),"chat");var c=service.send("office",input);var turn=c.turns().getLast();assertThat(c.mode()).isEqualTo("chat");assertThat(turn.brief()).isNull();assertThat(turn.skillId()).isEqualTo("auto");assertThat(turn.artifacts()).isEmpty();assertThat(resources.list("office",c.id())).isEmpty();assertThat(service.legacyResources("office",c.id())).isEmpty();assertThat(turn.systemInstructions()).contains("优先直接回应").doesNotContain("办公整理伙伴");assertThat(service.list("office","chat")).hasSize(1);assertThat(service.list("office","work")).isEmpty();assertThat(service.list("office")).isEmpty();
 }
 @Test void workRequiresExplicitBriefBeforeReservationAndModel()throws Exception {
  var c=id();var bad=new PartnerChatService.Input(c,id(),0,"任务","auto",List.of(),null,"work",brief(),false);
  assertThatThrownBy(()->service.send("office",bad)).hasMessageContaining("确认");assertThatThrownBy(()->service.get("office",c)).hasMessageContaining("不存在");
  assertThatThrownBy(()->service.send("office",new PartnerChatService.Input(c,id(),0,"任务","auto",List.of(),null,"work",new PartnerChatService.Brief("","文稿",""),true))).hasMessageContaining("必填");verify(model,never()).call(any(Prompt.class));
 }
 @Test void confirmedWorkAnalyzesMaterialsPublishesRealFilesAndRestores()throws Exception {
  var file=resources.upload("office",new MockMultipartFile("file","fixture.csv","text/csv","项目,数值\n甲,10\n乙,20".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  var input=new PartnerChatService.Input(id(),id(),0,"分析数据","auto",List.of(file.id()),null,"work",brief(),true);var c=service.send("office",input);var t=c.turns().getLast();assertThat(t.brief()).isEqualTo(brief());assertThat(t.materialContext()).contains("合计=30.0");assertThat(t.artifacts()).hasSize(4);for(var r:t.artifacts())assertThat(resources.bytes("office",r.id())).isNotEmpty();assertThat(t.events()).extracting(PartnerChatService.Event::label).contains("已确认工作目标与交付物","正在生成工作成果");try(var restored= new ServiceCloser(create(model))){assertThat(restored.value.get("office",c.id())).isEqualTo(c);}verify(model,times(1)).call(any(Prompt.class));
 }
 record ServiceCloser(PartnerChatService value)implements AutoCloseable {public void close(){value.close();}}
 @Test void modeAndAccountAreImmutableAndOldEntryCannotBypassWorkConfirmation()throws Exception {
  var workRequest=request(id(),"work");var c=service.send("office",workRequest);
  assertThatThrownBy(()->service.send("office",new PartnerChatService.Input(c.id(),id(),1,"继续"))).hasMessageContaining("其他工作空间");
  assertThatThrownBy(()->service.send("office",new PartnerChatService.Input(c.id(),id(),1,"继续","auto",List.of(),null,"chat",null,false))).hasMessageContaining("其他工作空间");
  assertThatThrownBy(()->api.page("office",c.id(),"chat",null,8)).hasMessageContaining("工作空间");assertThatThrownBy(()->api.stop("office",c.id(),"chat")).hasMessageContaining("工作空间");assertThatThrownBy(()->api.events("office",c.id(),"chat")).hasMessageContaining("工作空间");
  try(var ignored=UserContext.open(TaskWorkflowTest.B)){assertThat(api.list("office","work")).isEmpty();assertThatThrownBy(()->api.page("office",c.id(),"work",null,8)).hasMessageContaining("不存在");assertThatThrownBy(()->resources.bytes("office",c.turns().getLast().artifacts().getFirst().id())).hasMessageContaining("不存在");}
  verify(model,times(1)).call(any(Prompt.class));
 }
 @Test void legacyRecordsRemainLegacyWithoutRewritingOrAdoption()throws Exception {
  var c=service.send("office",new PartnerChatService.Input(id(),id(),0,"旧任务"));Path p=root.resolve(TaskWorkflowTest.A.id()).resolve("conversations").resolve(c.id()+".json");var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(p.toFile());node.remove("mode");for(var t:node.get("turns"))((com.fasterxml.jackson.databind.node.ObjectNode)t).remove("brief");json.writeValue(p.toFile(),node);byte[] before=Files.readAllBytes(p);
  assertThat(service.get("office",c.id()).mode()).isEqualTo("legacy");assertThat(service.list("office","work")).isEmpty();assertThatThrownBy(()->service.send("office",request(c.id(),"work"))).hasMessageContaining("其他工作空间");assertThat(Files.readAllBytes(p)).isEqualTo(before);assertThat(service.get("office",c.id()).turns().getFirst().answer()).isEqualTo("合成答复");verify(model,times(1)).call(any(Prompt.class));
 }
 @Test void duplicateIdDoesNotResubmitAndChangedBriefIsRejected()throws Exception {
  var input=request(id(),"work");var c=service.send("office",input);assertThat(service.send("office",input)).isEqualTo(c);
  assertThatThrownBy(()->service.send("office",new PartnerChatService.Input(input.conversationId(),input.requestId(),0,input.text(),"auto",List.of(),null,"work",new PartnerChatService.Brief("另一个目标","文稿",""),true))).hasMessageContaining("其他内容");verify(model,times(1)).call(any(Prompt.class));
 }
 @Test void disconnectOnlyStopsSubscriptionAndReconnectRestoresWithoutResubmission()throws Exception {
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);when(model.call(any(Prompt.class))).thenAnswer(call->{entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return PartnerChatServiceTest.answer("完成");});var input=request(id(),"chat");
  try {api.submit("office",input,"chat");assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();var first=api.events("office",input.conversationId(),"chat").take(1).blockLast(Duration.ofSeconds(3));assertThat(first.event()).isEqualTo("state");assertThat(service.get("office",input.conversationId()).turns().getLast().status()).isEqualTo("RUNNING");release.countDown();await(input.conversationId());var replay=api.events("office",input.conversationId(),"chat").collectList().block(Duration.ofSeconds(3));assertThat(replay).extracting(e->e.event()).containsExactly("state","done");verify(model,times(1)).call(any(Prompt.class));}finally{release.countDown();}
 }
 @Test void cancellationPreventsLateWorkArtifacts()throws Exception {
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);when(model.call(any(Prompt.class))).thenAnswer(call->{entered.countDown();release.await(5,TimeUnit.SECONDS);return PartnerChatServiceTest.answer("不能发布");});var input=request(id(),"work");var executor=Executors.newSingleThreadExecutor();
  try{var task=executor.submit(()->{try(var ignored=UserContext.open(TaskWorkflowTest.A)){return service.send("office",input);}});assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(api.stop("office",input.conversationId(),"work").turns().getLast().status()).isEqualTo("CANCELLED");release.countDown();var settled=task.get(5,TimeUnit.SECONDS);assertThat(settled.turns().getLast().status()).isEqualTo("CANCELLED");assertThat(settled.turns().getLast().answer()).isNull();assertThat(resources.list("office",input.conversationId())).isEmpty();}finally{release.countDown();executor.shutdownNow();}
 }
 @Test void sharedPolicyAndBudgetCannotBeBypassedBySwitchingWorkspace()throws Exception {
  work.policy("office",new WorkService.PartnerPolicy("RESTRICTED",false));assertThatThrownBy(()->service.send("office",request(id(),"chat"))).hasMessageContaining("限制");assertThatThrownBy(()->service.send("office",request(id(),"work"))).hasMessageContaining("限制");verify(model,never()).call(any(Prompt.class));work.policy("office",new WorkService.PartnerPolicy("ALLOW",true));
  var governance=new ModelGovernance(json,root.resolve("governance"),Clock.systemUTC(),new ModelGovernance.Limits(1,2,1,100000,128));service.close();service=create(governance.govern(model,"mock","fixture"));assertThat(service.send("office",request(id(),"chat")).turns().getLast().status()).isEqualTo("SUCCEEDED");assertThat(service.send("office",request(id(),"work")).turns().getLast().status()).isEqualTo("FAILED");assertThat(governance.summary().requests()).isEqualTo(1);verify(model,times(1)).call(any(Prompt.class));
 }
 @Test void invalidModeAndConflictingQueryAreRejectedWithoutModelCall()throws Exception {
  assertThatThrownBy(()->api.list("office","coding")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->api.list("office",null)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->api.submit("office",request(id(),"chat"),null)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->api.submit("office",request(id(),"chat"),"work")).hasMessageContaining("不一致");assertThatThrownBy(()->api.submit("office",new PartnerChatService.Input(id(),id(),0,"legacy"),null)).isInstanceOf(IllegalArgumentException.class);verify(model,never()).call(any(Prompt.class));
 }
}
