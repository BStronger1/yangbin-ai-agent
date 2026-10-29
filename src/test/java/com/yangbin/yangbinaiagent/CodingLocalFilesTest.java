package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.coding.*;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class CodingLocalFilesTest {
    @TempDir Path root; CodingService service; CodingServiceTest.Fake runtime; UserContext.Scope scope;
    @BeforeEach void setup()throws Exception {scope=UserContext.open(TaskWorkflowTest.A);runtime=new CodingServiceTest.Fake();service=new CodingService(new ObjectMapper(),runtime,root.toString(),true);}
    @AfterEach void close(){service.close();scope.close();}
    static String id(){return UUID.randomUUID().toString();}
    CodingService.Session fresh()throws Exception {var p=service.createProject(new CodingService.ProjectInput("local","local"));return service.createSession(new CodingService.SessionInput(p.id(),"local test","on-risk"));}
    CodingService.TurnInput turn(String client,String mode,String policy){return new CodingService.TurnInput(id(),"inspect local project",0,policy,null,null,mode,client);}
    @Test void localProjectsCannotFallBackToServerFilesOrTeamOrShell()throws Exception {
        var s=fresh();assertThat(service.projects().getFirst().source()).isEqualTo("local");
        assertThatThrownBy(()->service.files(s.projectId)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.file(s.projectId,"a.txt")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.putFile(s.projectId,new CodingService.FileInput("a.txt","x"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.diff(s.id)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.submit(s.id,turn(null,"auto","on-risk"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.submit(s.id,turn(id(),"team","on-risk"))).isInstanceOf(IllegalArgumentException.class);
        service.submit(s.id,turn(id(),"auto","on-risk"));
        for(int i=0;i<100&&runtime.request==null;i++)Thread.sleep(10);
        assertThat(runtime.request.execution()).containsEntry("localFiles",true);assertThat(runtime.request.allowShell()).isFalse();
    }
    @Test void explicitAllowIsPassedToLocalRuntimeWithoutEnablingServerShell()throws Exception {
        var s=fresh();service.submit(s.id,turn(id(),"auto","allow"));
        for(int i=0;i<100&&runtime.request==null;i++)Thread.sleep(10);
        assertThat(runtime.request).isNotNull();assertThat(runtime.request.approvalMode()).isEqualTo("allow");
        assertThat(runtime.request.execution()).containsEntry("localFiles",true);assertThat(runtime.request.allowShell()).isFalse();
        assertThat(service.session(s.id).turns.getLast().approvalMode).isEqualTo("allow");
    }
    @Test void requestsAreOwnerClientAndTurnBoundWithSingleClaimAndIdempotentReceipt()throws Exception {
        var s=fresh();String client=id(),request=id();var ready=new CountDownLatch(1);var done=new CountDownLatch(1);var release=new CountDownLatch(1);
        runtime.action=(r,e,q)->{e.accept(Map.of("type","local_fs","requestId",request,"operation","read","arguments",Map.of("path","a.txt")));ready.countDown();var result=q.poll(5,TimeUnit.SECONDS);assertThat(result).containsEntry("output","from local").containsEntry("type","local_fs_result");done.countDown();release.await(5,TimeUnit.SECONDS);};
        try {
            var turn=turn(client,"react","on-risk");service.submit(s.id,turn);assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->service.localRequests(s.id,id())).isInstanceOf(IllegalStateException.class);
            try(var other=UserContext.open(TaskWorkflowTest.B)){assertThatThrownBy(()->service.localRequests(s.id,client)).isInstanceOf(IllegalArgumentException.class);}
            var pending=service.localRequests(s.id,client);assertThat(pending).hasSize(1);assertThat(pending.getFirst()).containsEntry("turnId",turn.requestId()).containsEntry("claimed",false);
            var result=new CodingService.LocalResult(client,request,true,"from local");
            assertThatThrownBy(()->service.completeLocal(s.id,result)).isInstanceOf(IllegalStateException.class);
            service.claimLocal(s.id,client,request);assertThatThrownBy(()->service.claimLocal(s.id,client,request)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->service.completeLocal(s.id,new CodingService.LocalResult(client,request,true,"x".repeat(65537)))).isInstanceOf(IllegalArgumentException.class);
            service.completeLocal(s.id,result);service.completeLocal(s.id,result);assertThat(done.await(5,TimeUnit.SECONDS)).isTrue();assertThat(service.localRequests(s.id,client)).isEmpty();
            assertThat(service.session(s.id).events).noneMatch(e->"local_fs".equals(e.get("type"))); // transient operations never replay from history
        } finally {release.countDown();}
    }
    @Test void cancellationRevokesPendingOperations()throws Exception {
        var s=fresh();String client=id(),request=id();var ready=new CountDownLatch(1);
        runtime.action=(r,e,q)->{e.accept(Map.of("type","local_fs","requestId",request,"operation","write","arguments",Map.of("path","a","content","b")));ready.countDown();q.poll(5,TimeUnit.SECONDS);};
        service.submit(s.id,turn(client,"react","on-risk"));assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();service.cancel(s.id);
        assertThatThrownBy(()->service.claimLocal(s.id,client,request)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.completeLocal(s.id,new CodingService.LocalResult(client,request,true,"written"))).isInstanceOf(IllegalStateException.class);
    }
}
