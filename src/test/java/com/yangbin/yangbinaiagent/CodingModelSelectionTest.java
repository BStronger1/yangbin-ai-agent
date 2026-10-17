package com.yangbin.yangbinaiagent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.coding.*;
import com.yangbin.yangbinaiagent.models.*;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;

class CodingModelSelectionTest {
    @TempDir Path root;ObjectMapper json=new ObjectMapper();ModelProfileService profiles;CodingService coding;UserContext.Scope scope;Runtime runtime=new Runtime();
    static class Runtime implements CodingRuntime{
        volatile Request request;AtomicInteger calls=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        public Availability availability(){return new Availability(false,"没有系统密钥","");}
        public Availability availability(ModelProfileService.Resolved selected){return selected==null?availability():new Availability(true,"",selected.snapshot().model());}
        public void run(Request request,Consumer<Map<String,Object>> sink,BlockingQueue<Map<String,Object>> commands)throws Exception{this.request=request;calls.incrementAndGet();entered.countDown();release.await(5,TimeUnit.SECONDS);sink.accept(Map.of("type","text_delta","text","personal model complete"));}
    }
    @BeforeEach void setup()throws Exception{scope=UserContext.open(TaskWorkflowTest.A);profiles=new ModelProfileService(json,root.resolve("models").toString(),"");coding=new CodingService(json,runtime,root.resolve("coding").toString(),false,profiles);}
    @AfterEach void close(){runtime.release.countDown();coding.close();scope.close();}
    ModelProfileService.Profile model(String name,String key)throws Exception{return profiles.create(new ModelProfileService.Input(name,"openai-compatible","https://8.8.8.8/provider",name,key));}
    CodingService.Session fresh()throws Exception{var p=coding.createProject(new CodingService.ProjectInput("test"));return coding.createSession(new CodingService.SessionInput(p.id(),"test","allow"));}
    CodingService.Session completed(String id)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){var s=coding.session(id);if(s.status.equals("SUCCEEDED"))return s;Thread.sleep(10);}throw new AssertionError("worker not completed");}
    @Test void byokWorksWithoutSystemKeyAndFreezesModelAndSecretForRunningTurn()throws Exception{
        var p=model("first-model","first-private-key");var s=fresh();var input=new CodingService.TurnInput(UUID.randomUUID().toString(),"task",0,"allow",p.id());
        assertThat(coding.capabilities(p.id())).containsEntry("available",true);assertThat(coding.capabilities("system")).containsEntry("available",false);
        coding.submit(s.id,input);assertThat(runtime.entered.await(5,TimeUnit.SECONDS)).isTrue();profiles.update(p.id(),new ModelProfileService.Input("changed","anthropic","https://8.8.8.8/claude","second-model","second-private-key"));
        assertThat(runtime.request.model().apiKey()).isEqualTo("first-private-key");assertThat(runtime.request.model().snapshot().model()).isEqualTo("first-model");
        var pending=coding.session(s.id);assertThat(pending.turns.getFirst().modelSnapshot.provider()).isEqualTo("openai-compatible");assertThat(json.writeValueAsString(pending)).doesNotContain("first-private-key","second-private-key","ciphertext");
        runtime.release.countDown();completed(s.id);assertThat(coding.submit(s.id,input).revision).isEqualTo(1);assertThat(runtime.calls).hasValue(1);
        assertThatThrownBy(()->coding.submit(s.id,new CodingService.TurnInput(input.requestId(),"task",0,"allow","system"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void defaultChoiceIsFrozenForIdempotentReplayAfterProfileDeletion()throws Exception{
        var p=model("default-model","private-key");profiles.defaults(p.id());var s=fresh();var input=new CodingService.TurnInput(UUID.randomUUID().toString(),"task",0,"allow");coding.submit(s.id,input);assertThat(runtime.entered.await(5,TimeUnit.SECONDS)).isTrue();runtime.release.countDown();var done=completed(s.id);assertThat(done.turns.getFirst().modelProfileId).isEqualTo(p.id());
        profiles.delete(p.id());assertThat(coding.submit(s.id,input).turns.getFirst().modelProfileId).isEqualTo(p.id());assertThat(runtime.calls).hasValue(1);
    }
    @Test void otherOwnersCannotSelectPrivateProfileEvenOnTheirOwnSession()throws Exception{
        var p=model("owner-a","key");try(var other=UserContext.open(TaskWorkflowTest.B)){var s=fresh();assertThatThrownBy(()->coding.capabilities(p.id())).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->coding.submit(s.id,new CodingService.TurnInput(UUID.randomUUID().toString(),"task",0,"allow",p.id()))).isInstanceOf(IllegalArgumentException.class);}assertThat(runtime.calls).hasValue(0);
    }
}
