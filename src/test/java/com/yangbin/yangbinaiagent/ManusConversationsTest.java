package com.yangbin.yangbinaiagent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.chatmemory.ManusConversations;
import com.yangbin.yangbinaiagent.agent.BaseAgent;
import com.yangbin.yangbinaiagent.agent.model.AgentState;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
class ManusConversationsTest {
 @TempDir Path root;ManusConversations service;UserContext.Scope scope;String cid,rid;
 @BeforeEach void setup()throws Exception{scope=UserContext.open(TaskWorkflowTest.A);service=new ManusConversations(new ObjectMapper(),root.toString());cid=UUID.randomUUID().toString();rid=UUID.randomUUID().toString();}
 @AfterEach void close(){scope.close();}
 @Test void duplicateRequestsSurviveRestartWithoutExecution()throws Exception{
  var first=service.reserve(cid,rid,"task","system");assertThat(first.existing()).isFalse();assertThat(service.reserve(cid,rid,"task","system").existing()).isTrue();
  assertThatThrownBy(()->service.reserve(cid,rid,"different","system")).isInstanceOf(IllegalStateException.class);
  service=new ManusConversations(new ObjectMapper(),root.toString());assertThat(service.get(cid).turns().getFirst().status()).isEqualTo("INTERRUPTED");assertThat(service.reserve(cid,rid,"task","system").existing()).isTrue();
 }
 @Test void fiftyTurnContextPreservesEarlyAndRecentExcerptsBoundedly()throws Exception{
  for(int i=0;i<50;i++){String r=UUID.randomUUID().toString();service.reserve(cid,r,"goal-"+i+" "+"x".repeat(2000),"system");service.complete(cid,r,"SUCCEEDED","answer-"+i+" "+"y".repeat(3000));}
  var context=ManusConversations.context(service.get(cid));String text=context.stream().map(m->m.getText()).reduce("",String::concat);
  assertThat(text).contains("goal-0","goal-49","原文节选","已截断");assertThat(text.length()).isLessThan(48000);assertThat(context).hasSize(17);
  service=new ManusConversations(new ObjectMapper(),root.toString());assertThat(service.get(cid).turns()).hasSize(50);
 }
 @Test void isolationDeletionAndExplicitMemory()throws Exception{
  service.memory("explicit preference");service.reserve(cid,rid,"task","system");service.complete(cid,rid,"SUCCEEDED","answer");
  try(var other=UserContext.open(TaskWorkflowTest.B)){assertThat(service.list()).isEmpty();assertThat(service.memory()).isEmpty();assertThatThrownBy(()->service.get(cid)).isInstanceOf(IllegalArgumentException.class);}
  var next=service.reserve(cid,UUID.randomUUID().toString(),"next","system");assertThat(next.context().getFirst().getText()).contains("explicit preference");assertThatThrownBy(()->service.delete(cid)).isInstanceOf(IllegalStateException.class);
  service.complete(cid,next.conversation().turns().getLast().id(),"FAILED","");service.delete(cid);assertThat(service.list()).isEmpty();service.memory("");assertThat(service.memory()).isEmpty();
 }
 @Test void failedTurnNeverBecomesSuccessfulContext()throws Exception{service.reserve(cid,rid,"secret_failed","system");service.complete(cid,rid,"FAILED","untrusted partial");assertThat(ManusConversations.context(service.get(cid))).isEmpty();assertThatThrownBy(()->service.memory("x".repeat(4001))).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.get("../x")).isInstanceOf(IllegalArgumentException.class);}
 @Test void boundedLoopStopsAtLimitAndDoesNotReturnExceptionBody(){AtomicInteger calls=new AtomicInteger();var a=new BaseAgent(){public String step(){calls.incrementAndGet();return "result";}};a.setMaxSteps(2);List<String> status=new ArrayList<>();a.onFinished((s,t)->status.add(s));assertThat(a.run("task")).contains("result");assertThat(calls.get()).isEqualTo(2);assertThat(status).containsExactly("LIMIT_REACHED");
  var b=new BaseAgent(){public String step(){throw new IllegalStateException("private credential");}};assertThat(b.run("task")).doesNotContain("private credential");assertThat(b.getState()).isEqualTo(AgentState.ERROR);
 }

 @Test void cancellationRetainsSessionUntilWorkerActuallySettles()throws Exception{
  var entered=new java.util.concurrent.CountDownLatch(1);var interrupted=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var settled=new java.util.concurrent.CountDownLatch(1);var owner=UserContext.current();
  var agent=new BaseAgent(){public String step(){entered.countDown();while(release.getCount()>0){try{release.await();}catch(InterruptedException e){interrupted.countDown();}}return "late output";}};
  service.reserve(cid,rid,"task","system");service.attach(cid,rid,agent);
  agent.onFinished((status,text)->{try(var identity=UserContext.open(owner)){try{service.complete(cid,rid,status,text);}catch(Exception e){throw new RuntimeException(e);}}});
  agent.onSettled(()->{try(var identity=UserContext.open(owner)){service.release(cid);}settled.countDown();});
  try{agent.runStream("task");assertThat(entered.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();service.cancel(cid);assertThat(interrupted.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
   assertThatThrownBy(()->service.reserve(cid,UUID.randomUUID().toString(),"next","system")).isInstanceOf(IllegalStateException.class);
   assertThatThrownBy(()->service.delete(cid)).isInstanceOf(IllegalStateException.class);
   release.countDown();assertThat(settled.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(service.get(cid).turns().getLast().status()).isEqualTo("INTERRUPTED");assertThat(service.get(cid).turns().getLast().answer()).doesNotContain("late output");
   assertThat(service.reserve(cid,UUID.randomUUID().toString(),"next","system").existing()).isFalse();
  }finally{release.countDown();}
 }
 @Test void cancellationBeforeAttachCannotRestartReservedTask()throws Exception{
  service.reserve(cid,rid,"task","system");service.cancel(cid);
  assertThatThrownBy(()->service.attach(cid,rid,new BaseAgent(){public String step(){return "never";}})).isInstanceOf(IllegalStateException.class);
  assertThat(service.get(cid).turns().getLast().status()).isEqualTo("INTERRUPTED");
 }
 @Test void failedCompletionDoesNotLeaveWorkerOrReportSuccess()throws Exception{
  var settled=new java.util.concurrent.CountDownLatch(1);var statuses=new java.util.concurrent.CopyOnWriteArrayList<String>();
  var agent=new BaseAgent(){public String step(){setState(AgentState.FINISHED);return "answer";}};
  agent.onFinished((status,text)->{statuses.add(status);throw new IllegalStateException("storage unavailable");});agent.onSettled(settled::countDown);
  agent.runStream("task");assertThat(settled.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(agent.getState()).isEqualTo(AgentState.ERROR);assertThat(agent.getNotified().get()).isFalse();assertThat(statuses).containsExactly("SUCCEEDED","FAILED");
 }
}
