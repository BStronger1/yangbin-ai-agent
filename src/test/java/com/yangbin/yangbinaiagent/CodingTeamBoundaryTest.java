package com.yangbin.yangbinaiagent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.coding.*;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import static org.assertj.core.api.Assertions.*;
class CodingTeamBoundaryTest {
 @TempDir Path root;ObjectMapper json=new ObjectMapper();CodingServiceTest.Fake runtime;CodingService service;UserContext.Scope scope;
 @BeforeEach void setup()throws Exception{scope=UserContext.open(TaskWorkflowTest.A);runtime=new CodingServiceTest.Fake();service=new CodingService(json,runtime,root.toString(),false);}
 @AfterEach void close(){service.close();scope.close();}
 CodingService.Session fresh()throws Exception{var p=service.createProject(new CodingService.ProjectInput("team fixture"));return service.createSession(new CodingService.SessionInput(p.id(),"team","ask"));}
 String id(){return UUID.randomUUID().toString();}
 CodingService.Session done(String id)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){var s=service.session(id);if(!Set.of("RUNNING","WAITING_APPROVAL").contains(s.status))return s;Thread.sleep(10);}throw new AssertionError("not completed");}
 @Test void trustedIdentityPartialOutcomeAndHashCheckedArtifactAreOwnerScoped()throws Exception{
  var s=fresh();String turn=id(),artifact=id();var fileRef=new java.util.concurrent.atomic.AtomicReference<Path>();
  runtime.action=(r,e,q)->{assertThat(((Map<?,?>)r.execution().get("identity")).get("accountId")).isEqualTo(TaskWorkflowTest.A.id());assertThat(r.execution().get("mode")).isEqualTo("team");Path dir=r.sessionDir().resolve("teams/fixture");Files.createDirectories(dir);Path file=dir.resolve("result.txt");fileRef.set(file);Files.writeString(file,"staged evidence");byte[] bytes=Files.readAllBytes(file);String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));json.writeValue(r.sessionDir().resolve("team-results-"+turn+".json").toFile(),Map.of("artifacts",List.of(Map.of("id",artifact,"name","result.txt","internalPath",file.toString(),"sha256",hash,"size",bytes.length))));e.accept(Map.of("type","status","status","PARTIAL"));};
  service.submit(s.id,new CodingService.TurnInput(turn,"团队说明",0,"ask",null,null,"team"));assertThat(done(s.id).status).isEqualTo("PARTIAL");assertThat(service.teamArtifact(s.id,turn,artifact).get("content")).isEqualTo("staged evidence");
  try(var other=UserContext.open(TaskWorkflowTest.B)){assertThatThrownBy(()->service.teamArtifact(s.id,turn,artifact)).isInstanceOf(IllegalArgumentException.class);}
  Files.writeString(fileRef.get(),"tampered");assertThatThrownBy(()->service.teamArtifact(s.id,turn,artifact)).isInstanceOf(IllegalStateException.class);
 }
 @Test void childCancelChecksMembershipAndDoesNotCancelParent()throws Exception{
  var s=fresh();String child=id(),turn=id();var ready=new CountDownLatch(1);var completed=new CountDownLatch(1);
  runtime.action=(r,e,q)->{e.accept(Map.of("type","task","identity",Map.of("taskId",child,"parentTaskId",turn),"status","RUNNING"));ready.countDown();var command=q.poll(5,TimeUnit.SECONDS);assertThat(command.get("type")).isEqualTo("cancel_task");assertThat(command.get("taskId")).isEqualTo(child);completed.countDown();e.accept(Map.of("type","status","status","PARTIAL"));};
  service.submit(s.id,new CodingService.TurnInput(turn,"团队说明",0,"ask",null,null,"team"));assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->service.cancelChild(s.id,id())).isInstanceOf(IllegalArgumentException.class);
  try(var other=UserContext.open(TaskWorkflowTest.B)){assertThatThrownBy(()->service.cancelChild(s.id,child)).isInstanceOf(IllegalArgumentException.class);}
  service.cancelChild(s.id,child);assertThat(completed.await(5,TimeUnit.SECONDS)).isTrue();assertThat(done(s.id).status).isEqualTo("PARTIAL");
 }
 @Test void resolvingOneParallelApprovalKeepsOtherPendingVisible()throws Exception{
  var s=fresh();String one=id(),two=id();CountDownLatch ready=new CountDownLatch(1),release=new CountDownLatch(1);
  runtime.action=(r,e,q)->{e.accept(Map.of("type","approval_required","approvalId",one));e.accept(Map.of("type","approval_required","approvalId",two));e.accept(Map.of("type","approval_resolved","approvalId",one,"approved",true));ready.countDown();release.await(5,TimeUnit.SECONDS);e.accept(Map.of("type","approval_resolved","approvalId",two,"approved",true));e.accept(Map.of("type","status","status","SUCCEEDED"));};
  service.submit(s.id,new CodingService.TurnInput(id(),"parallel approvals",0,"ask",null,null,"team"));assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();assertThat(service.session(s.id).status).isEqualTo("WAITING_APPROVAL");release.countDown();assertThat(done(s.id).status).isEqualTo("SUCCEEDED");
 }
}
