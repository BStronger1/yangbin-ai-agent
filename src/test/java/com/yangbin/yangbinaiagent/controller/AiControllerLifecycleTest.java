package com.yangbin.yangbinaiagent.controller;
import com.yangbin.yangbinaiagent.app.LoveApp;
import com.yangbin.yangbinaiagent.security.UserContext;
import com.yangbin.yangbinaiagent.governance.ModelGovernance;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class AiControllerLifecycleTest {
 static class Emitter extends SseEmitter {
  Runnable timeout=()->{},completion=()->{};Consumer<Throwable> error=e->{};boolean failSend;volatile Throwable failure;
  @Override public void onTimeout(Runnable callback){timeout=callback;}
  @Override public void onError(Consumer<Throwable> callback){error=callback;}
  @Override public void onCompletion(Runnable callback){completion=callback;}
  @Override public void send(Object value)throws IOException{if(failSend)throw new IOException("private transport detail");}
  @Override public void complete(){completion.run();}
  @Override public void completeWithError(Throwable error){failure=error;completion.run();}
 }
 @Test void emitterTimeoutErrorAndCompletionDisposeSubscription()throws Exception{
  for(String signal:new String[]{"timeout","error","completion"}){var subscribed=new CountDownLatch(1);var cancelled=new CountDownLatch(1);var emitter=new Emitter();AiController.relay(Flux.<String>create(sink->{sink.onCancel(cancelled::countDown);subscribed.countDown();}),emitter);assertThat(subscribed.await(5,TimeUnit.SECONDS)).isTrue();switch(signal){case "timeout"->emitter.timeout.run();case "error"->emitter.error.accept(new IOException());default->emitter.completion.run();}assertThat(cancelled.await(5,TimeUnit.SECONDS)).isTrue();}
 }
 @Test void failedSendCancelsAndDoesNotExposeTransportDetails()throws Exception{var cancelled=new CountDownLatch(1);var emitter=new Emitter();emitter.failSend=true;AiController.relay(Flux.<String>create(sink->{sink.onCancel(cancelled::countDown);sink.next("part");}),emitter);assertThat(cancelled.await(5,TimeUnit.SECONDS)).isTrue();for(int i=0;i<100&&emitter.failure==null;i++)Thread.sleep(5);assertThat(emitter.failure).hasMessage("消息传输已中断").hasNoCause();}
 @Test void deferredEventsRetainAuthenticatedIdentityAndRequestId(){var app=mock(LoveApp.class);var controller=new AiController();ReflectionTestUtils.setField(controller,"loveApp",app);var user=new UserContext.User("a".repeat(32),"user");var seenUser=new AtomicReference<UserContext.User>();var seenTrace=new AtomicReference<String>();when(app.doChatByStream(eq("hello"),eq("chat"),eq("chat"),any())).thenAnswer(call->{seenUser.set(UserContext.current());seenTrace.set(MDC.get("requestId"));return Flux.just("answer");});Flux<?> result;try(var ignored=UserContext.open(user);var trace=MDC.putCloseable("requestId","b".repeat(32))){result=controller.events("hello","chat","chat");}assertThat(result.collectList().block(Duration.ofSeconds(5))).hasSize(2);assertThat(seenUser.get()).isEqualTo(user);assertThat(seenTrace.get()).isEqualTo("b".repeat(32));}
 @Test void safeClientErrorsNeverEchoExceptionBodies(){var controller=new AiController();var bad=controller.invalid(new IllegalArgumentException("private credential"));var conflict=controller.conflict(new IllegalStateException("private credential"));var limited=controller.limited(new ModelGovernance.Denied("private credential"));assertThat(bad.getStatusCode().value()).isEqualTo(400);assertThat(conflict.getStatusCode().value()).isEqualTo(409);assertThat(limited.getStatusCode().value()).isEqualTo(429);assertThat(bad.getBody().toString()+conflict.getBody()+limited.getBody()).doesNotContain("private credential");}
}
