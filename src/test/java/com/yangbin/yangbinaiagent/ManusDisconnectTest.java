package com.yangbin.yangbinaiagent;
import com.yangbin.yangbinaiagent.agent.BaseAgent;
import com.yangbin.yangbinaiagent.agent.model.AgentState;
import org.junit.jupiter.api.*;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;
class ManusDisconnectTest {
 UserContext.Scope scope;
 @BeforeEach void authenticate(){scope=UserContext.open(TaskWorkflowTest.A);}
 @AfterEach void releaseIdentity(){scope.close();}
 static class Connection extends SseEmitter {
  Consumer<Throwable> error; Runnable completion; boolean broken;
  @Override public void onError(Consumer<Throwable> c){error=c;}
  @Override public void onCompletion(Runnable c){completion=c;}
  @Override public void onTimeout(Runnable c){}
  @Override public void send(Object data)throws IOException{if(broken)throw new IOException("browser gone");}
  @Override public void complete(){if(completion!=null)completion.run();}
  void disconnect(){broken=true;error.accept(new IOException("browser gone"));}
 }
 static class Job extends BaseAgent {
  final Connection connection=new Connection();
  final CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1), done=new CountDownLatch(1), interrupted=new CountDownLatch(1);
  final AtomicReference<String> result=new AtomicReference<>(), body=new AtomicReference<>();
  final long timeout;
  Job(long timeout){this.timeout=timeout;onFinished((s,t)->{result.set(s);body.set(t);});onSettled(done::countDown);}
  @Override protected long streamTimeoutMillis(){return timeout;}
  @Override protected SseEmitter streamEmitter(long timeout){return connection;}
  @Override public String step(){entered.countDown();try{release.await();setState(AgentState.FINISHED);return "one result";}catch(InterruptedException e){interrupted.countDown();Thread.currentThread().interrupt();return "late result";}}
 }
 @Test void durableDisconnectKeepsWorkerAndPersistsResult()throws Exception{
  var j=new Job(5000);j.runStream("task",true);
  try{assertThat(j.entered.await(3,TimeUnit.SECONDS)).isTrue();j.connection.disconnect();assertThat(j.interrupted.await(100,TimeUnit.MILLISECONDS)).isFalse();j.release.countDown();assertThat(j.done.await(3,TimeUnit.SECONDS)).isTrue();assertThat(j.result.get()).isEqualTo("SUCCEEDED");assertThat(j.body.get()).contains("one result");}finally{j.cancel();j.release.countDown();}
 }
 @Test void writeFailureDetachesWithoutChangingSavedOutcome()throws Exception{
  var j=new Job(5000);j.runStream("task",true);
  try{assertThat(j.entered.await(3,TimeUnit.SECONDS)).isTrue();j.connection.broken=true;j.release.countDown();assertThat(j.done.await(3,TimeUnit.SECONDS)).isTrue();assertThat(j.result.get()).isEqualTo("SUCCEEDED");}finally{j.cancel();j.release.countDown();}
 }
 @Test void detachedJobStillHonorsIndependentDeadline()throws Exception{
  var j=new Job(500);j.runStream("task",true);
  try{assertThat(j.entered.await(3,TimeUnit.SECONDS)).isTrue();j.connection.disconnect();assertThat(j.interrupted.await(3,TimeUnit.SECONDS)).isTrue();assertThat(j.done.await(3,TimeUnit.SECONDS)).isTrue();assertThat(j.result.get()).isEqualTo("INTERRUPTED");assertThat(j.body.get()).doesNotContain("late result");}finally{j.cancel();j.release.countDown();}
 }
 @Test void explicitStopStillCancelsDurableJob()throws Exception{
  var j=new Job(5000);j.runStream("task",true);
  try{assertThat(j.entered.await(3,TimeUnit.SECONDS)).isTrue();j.cancel();assertThat(j.done.await(3,TimeUnit.SECONDS)).isTrue();assertThat(j.result.get()).isEqualTo("INTERRUPTED");}finally{j.release.countDown();}
 }
 @Test void nonDurableCompatibilityStillCancelsOnDisconnect()throws Exception{
  var j=new Job(5000);j.runStream("task");
  try{assertThat(j.entered.await(3,TimeUnit.SECONDS)).isTrue();j.connection.disconnect();assertThat(j.done.await(3,TimeUnit.SECONDS)).isTrue();assertThat(j.result.get()).isEqualTo("INTERRUPTED");}finally{j.cancel();j.release.countDown();}
 }
}
