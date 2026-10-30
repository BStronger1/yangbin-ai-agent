package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.coding.*;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class CodingFileRecoveryTest {
    @TempDir Path root;ObjectMapper json=new ObjectMapper();CodingService service;CodingServiceTest.Fake runtime;UserContext.Scope user;
    @BeforeEach void setup()throws Exception{user=UserContext.open(TaskWorkflowTest.A);runtime=new CodingServiceTest.Fake();service=new CodingService(json,runtime,root.toString(),false);}
    @AfterEach void close(){service.close();user.close();}
    CodingService.Session fresh()throws Exception{var p=service.createProject(new CodingService.ProjectInput("recovery"));service.putFile(p.id(),new CodingService.FileInput("main.txt","base"));return service.createSession(new CodingService.SessionInput(p.id(),"crash","allow"));}
    CodingService.TurnInput input(int rev){return new CodingService.TurnInput(UUID.randomUUID().toString(),"edit once",rev,"allow");}
    CodingService.Session done(String id)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){var s=service.session(id);if(!Set.of("RUNNING","WAITING_APPROVAL").contains(s.status))return s;Thread.sleep(15);}throw new AssertionError("runtime did not exit");}
    void crash(String stage){runtime.action=(request,events,commands)->{
        String node=System.getProperty("recovery.node","node");
        var process=new ProcessBuilder(node,Path.of("coding-agent/test/fixtures/file-crash.mjs").toAbsolutePath().toString(),request.workspace().toString(),request.execution().get("fileJournalDir").toString(),Base64.getEncoder().encodeToString(json.writeValueAsBytes(request.execution().get("identity"))),stage).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isNotZero();throw new java.io.IOException("worker died before result");
    };}
    @SuppressWarnings("unchecked") List<Map<String,Object>> operations(Map<String,Object> report){return (List<Map<String,Object>>)report.get("operations");}
    @ParameterizedTest @ValueSource(strings={"prepared","staged","replaced","committed"})
    void realNodeCrashIsReconciledAndWholeTaskIsNotReplayed(String stage)throws Exception{
        var s=fresh();var request=input(0);crash(stage);service.submit(s.id,request);assertThat(done(s.id).status).isEqualTo("FAILED");
        boolean changed=Set.of("replaced","committed").contains(stage);assertThat(service.file(s.projectId,"main.txt").content()).isEqualTo(changed?"base+once":"base");
        var report=service.recovery(s.projectId);assertThat(report).containsEntry("required",true).containsEntry("canAccept",true).containsEntry("accepted",false);
        assertThat(operations(report)).singleElement().satisfies(op->assertThat(op).containsEntry("observation",changed?"AFTER_MATCH":"BEFORE_MATCH").containsEntry("state",stage.equals("committed")?"APPLIED":"PREPARED"));
        assertThat(service.submit(s.id,request).status).isEqualTo("FAILED");assertThat(runtime.calls).hasValue(1);
        assertThatThrownBy(()->service.submit(s.id,input(1))).isInstanceOf(IllegalStateException.class).hasMessageContaining("核对");
        var other=service.createSession(new CodingService.SessionInput(s.projectId,"new window","allow"));assertThatThrownBy(()->service.submit(other.id,input(0))).isInstanceOf(IllegalStateException.class);
        service.close();service=new CodingService(json,runtime,root.toString(),false);report=service.recovery(s.projectId);
        assertThat(operations(report)).hasSize(1);assertThat(runtime.calls).hasValue(1);
        service.acceptRecovery(s.projectId,new CodingService.RecoveryAcceptance(report.get("token").toString(),true));
        runtime.action=(r,e,q)->e.accept(Map.of("type","text_delta","text","continue by inspection, no mutation"));service.submit(s.id,input(1));done(s.id);
        assertThat(runtime.request.execution()).containsEntry("recoveredFromInterruption",true);assertThat(runtime.calls).hasValue(2);assertThat(service.file(s.projectId,"main.txt").content()).isEqualTo(changed?"base+once":"base");assertThat(service.recovery(s.projectId)).containsEntry("required",false);
    }
    @Test void changedFilesInvalidateReviewAndAcceptanceAndAreNeverRestoredAutomatically()throws Exception{
        var s=fresh();crash("replaced");service.submit(s.id,input(0));done(s.id);var old=service.recovery(s.projectId);
        service.putFile(s.projectId,new CodingService.FileInput("main.txt","user edit"));
        assertThatThrownBy(()->service.acceptRecovery(s.projectId,new CodingService.RecoveryAcceptance(old.get("token").toString(),true))).isInstanceOf(IllegalStateException.class);
        var current=service.recovery(s.projectId);assertThat(operations(current).getFirst()).containsEntry("observation","DIVERGED");
        service.acceptRecovery(s.projectId,new CodingService.RecoveryAcceptance(current.get("token").toString(),true));
        service.putFile(s.projectId,new CodingService.FileInput("main.txt","another user edit"));assertThatThrownBy(()->service.submit(s.id,input(1))).isInstanceOf(IllegalStateException.class);
        assertThat(service.file(s.projectId,"main.txt").content()).isEqualTo("another user edit");
    }
    @Test void liveOrUnverifiableWriterBlocksReviewAndOtherAccountCannotInspect()throws Exception{
        var s=fresh();crash("prepared");service.submit(s.id,input(0));done(s.id);
        var journal=Path.of(runtime.request.execution().get("fileJournalDir").toString());json.writeValue(journal.resolve("writer.lock").toFile(),Map.of("pid",ProcessHandle.current().pid()));
        var report=service.recovery(s.projectId);assertThat(report).containsEntry("canAccept",false);assertThatThrownBy(()->service.acceptRecovery(s.projectId,new CodingService.RecoveryAcceptance(report.get("token").toString(),true))).isInstanceOf(IllegalStateException.class);
        try(var ignored=UserContext.open(TaskWorkflowTest.B)){assertThatThrownBy(()->service.recovery(s.projectId)).isInstanceOf(IllegalArgumentException.class);}
        Files.writeString(journal.resolve("writer.lock"),"broken");assertThat(service.recovery(s.projectId)).containsEntry("canAccept",false);
    }
    @Test void javaRestartDoesNotTreatOrphanNodeAsGone()throws Exception{
        var s=fresh();s.status="RUNNING";var turn=new CodingService.Turn();turn.id=UUID.randomUUID().toString();turn.status="RUNNING";s.turns.add(turn);
        json.writeValue(root.resolve(TaskWorkflowTest.A.id()).resolve("sessions").resolve(s.id+".json").toFile(),s);
        Path state=root.resolve(TaskWorkflowTest.A.id()).resolve("runtime").resolve(s.id);Files.createDirectories(state);json.writeValue(state.resolve("worker.json").toFile(),Map.of("pid",ProcessHandle.current().pid()));
        service.close();service=new CodingService(json,runtime,root.toString(),false);assertThat(service.session(s.id).status).isEqualTo("INTERRUPTED");assertThat(service.recovery(s.projectId)).containsEntry("canAccept",false);assertThat(runtime.calls).hasValue(0);
    }
    @Test void restartAfterNewTurnPersistenceRetainsRecoveryGateBeforeWorkerStart()throws Exception{
        var s=fresh();crash("replaced");service.submit(s.id,input(0));done(s.id);
        var report=service.recovery(s.projectId);service.acceptRecovery(s.projectId,new CodingService.RecoveryAcceptance(report.get("token").toString(),true));
        s=service.session(s.id);s.turns.getLast().recoveryRequired=false;
        var next=new CodingService.Turn();next.id=UUID.randomUUID().toString();next.status="RUNNING";s.turns.add(next);s.status="RUNNING";
        json.writeValue(root.resolve(TaskWorkflowTest.A.id()).resolve("sessions").resolve(s.id+".json").toFile(),s);
        service.close();service=new CodingService(json,runtime,root.toString(),false);
        assertThat(service.session(s.id).status).isEqualTo("INTERRUPTED");
        assertThat(service.recovery(s.projectId)).containsEntry("required",true).containsEntry("accepted",false);
        var restored=service.session(s.id);assertThatThrownBy(()->service.submit(restored.id,input(restored.revision))).isInstanceOf(IllegalStateException.class);
        assertThat(runtime.calls).hasValue(1);assertThat(service.file(s.projectId,"main.txt").content()).isEqualTo("base+once");
    }
    @Test void corruptJournalFailsClosedAndLocalFilesAreExplicitlyUnverified()throws Exception{
        var s=fresh();crash("replaced");service.submit(s.id,input(0));done(s.id);Path journal=Path.of(runtime.request.execution().get("fileJournalDir").toString());
        try(var files=Files.list(journal)){Path record=files.filter(p->p.toString().endsWith(".json")).findFirst().orElseThrow();Files.writeString(record,"{broken");}
        assertThat(service.recovery(s.projectId)).containsEntry("canAccept",false);assertThatThrownBy(()->service.submit(s.id,input(1))).isInstanceOf(IllegalStateException.class);
        var project=service.createProject(new CodingService.ProjectInput("local","local"));var local=service.createSession(new CodingService.SessionInput(project.id(),"local","allow"));
        runtime.action=(r,e,q)->{throw new java.io.IOException("local result lost");};
        service.submit(local.id,new CodingService.TurnInput(UUID.randomUUID().toString(),"edit",0,"allow",null,null,"react",UUID.randomUUID().toString()));done(local.id);
        assertThat(service.recovery(project.id())).containsEntry("scope","LOCAL_UNVERIFIED").containsEntry("required",true);assertThat(operations(service.recovery(project.id()))).isEmpty();
    }
}
