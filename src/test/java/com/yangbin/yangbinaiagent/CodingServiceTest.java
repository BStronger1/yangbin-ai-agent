package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.coding.*;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;

class CodingServiceTest {
    @TempDir Path root;ObjectMapper json=new ObjectMapper();CodingService service;UserContext.Scope scope;Fake runtime;
    interface Action{void run(CodingRuntime.Request r,Consumer<Map<String,Object>> e,BlockingQueue<Map<String,Object>> q)throws Exception;}
    static class Fake implements CodingRuntime {
        final AtomicInteger calls=new AtomicInteger();volatile Request request;volatile Action action=(r,e,q)->e.accept(Map.of("type","text_delta","text","完成"));
        public Map<String,Object> shellAvailability(){return Map.of("available",true);}
        public Availability availability(){return new Availability(true,"","offline-test");}
        public void run(Request r,Consumer<Map<String,Object>> e,BlockingQueue<Map<String,Object>> q)throws Exception{calls.incrementAndGet();request=r;action.run(r,e,q);}
    }
    @BeforeEach void setup()throws Exception{scope=UserContext.open(TaskWorkflowTest.A);runtime=new Fake();service=new CodingService(json,runtime,root.toString(),true);}
    @AfterEach void finish(){service.close();scope.close();}
    CodingService.Session fresh()throws Exception{var p=service.createProject(new CodingService.ProjectInput("测试项目"));return service.createSession(new CodingService.SessionInput(p.id(),"任务","on-risk"));}
    static String id(){return UUID.randomUUID().toString();}
    CodingService.TurnInput input(int rev){return new CodingService.TurnInput(id(),"请修改文件",rev,"on-risk");}
    CodingService.Session waitDone(String id)throws Exception{long until=System.nanoTime()+Duration.ofSeconds(5).toNanos();while(System.nanoTime()<until){var s=service.session(id);if(Set.of("SUCCEEDED","FAILED","INTERRUPTED","CANCELLED").contains(s.status))return s;Thread.sleep(10);}throw new AssertionError("worker did not finish");}
    @Test void actualFileChangePersistsAndDuplicateDoesNotCallAgain()throws Exception{
        var s=fresh();service.putFile(s.projectId,new CodingService.FileInput("src/main.txt","before"));
        runtime.action=(r,e,q)->{Files.writeString(r.workspace().resolve("src/main.txt"),"after");e.accept(Map.of("type","text_delta","text","修改完成"));};
        var input=input(0);service.submit(s.id,input);var done=waitDone(s.id);assertThat(done.status).isEqualTo("SUCCEEDED");assertThat(done.revision).isEqualTo(1);assertThat(runtime.request.allowShell()).isFalse();
        assertThat(service.diff(s.id)).containsExactly(new CodingService.Change("src/main.txt","before","after","modified"));assertThat(service.submit(s.id,input).status).isEqualTo("SUCCEEDED");assertThat(runtime.calls).hasValue(1);
        assertThatThrownBy(()->service.submit(s.id,new CodingService.TurnInput(input.requestId(),"different",0,"on-risk"))).isInstanceOf(IllegalStateException.class);
        try(var restored=new AutoService(new CodingService(json,runtime,root.toString(),true))){assertThat(restored.value.session(s.id).turns.getFirst().prompt).isEqualTo(input.prompt());}
    }
    record AutoService(CodingService value)implements AutoCloseable{public void close(){value.close();}}
    @Test void ownershipPreventsReadingEditingAndApprovingOtherAccounts()throws Exception{
        var s=fresh();service.putFile(s.projectId,new CodingService.FileInput("private.txt","private"));
        try(var other=UserContext.open(TaskWorkflowTest.B)){
            assertThat(service.projects()).isEmpty();assertThat(service.sessions()).isEmpty();
            assertThatThrownBy(()->service.session(s.id)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->service.file(s.projectId,"private.txt")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->service.approve(s.id,"approval",true)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void rejectsTraversalPrivateFilesWindowsAliasesAndOversize()throws Exception{
        var s=fresh();for(String path:List.of("../x","a/../../x","/etc/passwd","C:/secret","a\\b",".env",".ENV.local",".Git/config",".SSH/key","a./b","a /b","CON","nul.txt","com1.log")){
            assertThatThrownBy(()->service.putFile(s.projectId,new CodingService.FileInput(path,"x"))).as(path).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->service.putFile(s.projectId,new CodingService.FileInput("big.txt","x".repeat(262145)))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void approvalIsOwnerBoundSingleUseAndModeIsFrozen()throws Exception{
        var s=fresh();var approval=new CountDownLatch(1);var release=new CountDownLatch(1);
        runtime.action=(r,e,q)->{e.accept(Map.of("type","approval_required","approvalId","tool-1","toolName","write","arguments",Map.of("path","x")));approval.countDown();var command=q.poll(5,TimeUnit.SECONDS);assertThat(command).containsEntry("approved",false);e.accept(Map.of("type","approval_resolved","approvalId","tool-1","approved",false,"reason","rejected"));release.await(5,TimeUnit.SECONDS);};
        try{service.submit(s.id,input(0));assertThat(approval.await(5,TimeUnit.SECONDS)).isTrue();assertThat(service.session(s.id).status).isEqualTo("WAITING_APPROVAL");
            assertThatThrownBy(()->service.approve(s.id,"unknown",true)).isInstanceOf(IllegalStateException.class);
            service.approve(s.id,"tool-1",false);assertThatThrownBy(()->service.approve(s.id,"tool-1",true)).isInstanceOf(IllegalStateException.class);
            assertThat(runtime.request.approvalMode()).isEqualTo("on-risk");release.countDown();assertThat(waitDone(s.id).status).isEqualTo("SUCCEEDED");assertThat(service.session(s.id).events.stream().filter(e->"approval_resolved".equals(e.get("type"))).count()).isEqualTo(1);
        }finally{release.countDown();}
    }
    @Test void runtimeExpiredApprovalClosesPendingAndCannotBeApprovedAgain()throws Exception{
        var s=fresh();var expired=new CountDownLatch(1);var release=new CountDownLatch(1);
        runtime.action=(r,e,q)->{
            e.accept(Map.of("type","approval_required","approvalId","expires","toolName","write","arguments",Map.of("path","x")));
            e.accept(Map.of("type","approval_resolved","approvalId","unknown","approved",false,"reason","expired"));
            e.accept(Map.of("type","approval_resolved","approvalId","expires","approved",false,"reason","expired"));
            expired.countDown();release.await(5,TimeUnit.SECONDS);
        };
        try{service.submit(s.id,input(0));assertThat(expired.await(5,TimeUnit.SECONDS)).isTrue();var running=service.session(s.id);
            assertThat(running.status).isEqualTo("RUNNING");assertThat(running.turns.getLast().status).isEqualTo("RUNNING");
            assertThat(running.events.stream().filter(e->"approval_resolved".equals(e.get("type"))).toList()).singleElement().satisfies(e->assertThat(e).containsEntry("approvalId","expires").containsEntry("reason","expired"));
            assertThatThrownBy(()->service.approve(s.id,"expires",true)).isInstanceOf(IllegalStateException.class);
            release.countDown();assertThat(waitDone(s.id).status).isEqualTo("SUCCEEDED");
        }finally{release.countDown();}
    }
    @Test void cancellationBlocksNewMutationUntilWorkerReallyExits()throws Exception{
        var s=fresh();var started=new CountDownLatch(1);var release=new CountDownLatch(1);var commandSeen=new CountDownLatch(1);
        runtime.action=(r,e,q)->{started.countDown();assertThat(q.poll(5,TimeUnit.SECONDS)).containsEntry("type","cancel");commandSeen.countDown();release.await(5,TimeUnit.SECONDS);e.accept(Map.of("type","text_delta","text","late response"));};
        try{service.submit(s.id,input(0));assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();assertThat(service.cancel(s.id).status).isEqualTo("CANCELLED");assertThat(commandSeen.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->service.submit(s.id,input(1))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->service.putFile(s.projectId,new CodingService.FileInput("x","y"))).isInstanceOf(IllegalStateException.class);
            release.countDown();long until=System.nanoTime()+Duration.ofSeconds(5).toNanos();while(true){try{service.putFile(s.projectId,new CodingService.FileInput("x","y"));break;}catch(IllegalStateException e){if(System.nanoTime()>until)throw e;Thread.sleep(10);}}
            assertThat(service.session(s.id).events).noneMatch(e->"late response".equals(e.get("text")));
        }finally{release.countDown();}
    }
    @Test void restartMarksInflightInterruptedWithoutReplayingModel()throws Exception{
        var s=fresh();s.status="WAITING_APPROVAL";Path record=root.resolve(TaskWorkflowTest.A.id()).resolve("sessions").resolve(s.id+".json");json.writeValue(record.toFile(),s);
        try(var restored=new AutoService(new CodingService(json,runtime,root.toString(),true))){assertThat(restored.value.session(s.id).status).isEqualTo("INTERRUPTED");assertThatThrownBy(()->restored.value.approve(s.id,"old",true)).isInstanceOf(IllegalStateException.class);}assertThat(runtime.calls).hasValue(0);
    }
    @Test void shellRequiresBothAdminIdentityAndHostSwitch()throws Exception{
        assertThat(service.capabilities()).containsEntry("shellEnabled",false);
        try(var admin=UserContext.open(UserContext.ADMIN)){
            assertThat(service.capabilities()).containsEntry("shellEnabled",true);var s=fresh();service.submit(s.id,input(0));waitDone(s.id);assertThat(runtime.request.allowShell()).isTrue();
            try(var disabled=new AutoService(new CodingService(json,runtime,root.resolve("disabled").toString(),false))){assertThat(disabled.value.capabilities()).containsEntry("shellEnabled",false);}
        }
    }

    @Test void globalCapacityAndRequestTraceSurviveBackgroundDispatch()throws Exception{
        org.springframework.test.util.ReflectionTestUtils.setField(service,"globalConcurrency",1);
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var trace=new java.util.concurrent.atomic.AtomicReference<String>();
        runtime.action=(r,e,q)->{trace.set(org.slf4j.MDC.get("requestId"));started.countDown();release.await(5,TimeUnit.SECONDS);};
        try{var s=fresh();try(var mdc=org.slf4j.MDC.putCloseable("requestId","a".repeat(32))){service.submit(s.id,input(0));}
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();assertThat(trace.get()).isEqualTo("a".repeat(32));
            try(var other=UserContext.open(TaskWorkflowTest.B)){var second=fresh();assertThatThrownBy(()->service.submit(second.id,input(0))).isInstanceOf(IllegalStateException.class).hasMessageContaining("容量");assertThat(service.session(second.id).turns).isEmpty();}
            release.countDown();waitDone(s.id);
        }finally{release.countDown();}
    }

    @Test void cancellationDeniesLaterModelPermission()throws Exception{
        var started=new CountDownLatch(1);var requestSent=new CountDownLatch(1);var release=new CountDownLatch(1);
        var ledger=new com.yangbin.yangbinaiagent.governance.ModelGovernance(new ObjectMapper(),root.resolve("ledger").toString(),2,8,100,200000,4096);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"governance",ledger);
        runtime.action=(r,e,q)->{started.countDown();assertThat(q.poll(3,TimeUnit.SECONDS)).containsEntry("type","cancel");e.accept(Map.of("type","model_request","requestId",id(),"reservedTokens",100));assertThat(q.poll(3,TimeUnit.SECONDS)).containsEntry("approved",false);requestSent.countDown();release.await(3,TimeUnit.SECONDS);};
        try{var s=fresh();service.submit(s.id,input(0));assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();service.cancel(s.id);assertThat(requestSent.await(3,TimeUnit.SECONDS)).isTrue();assertThat(ledger.summary().requests()).isZero();}finally{release.countDown();}
    }
}
