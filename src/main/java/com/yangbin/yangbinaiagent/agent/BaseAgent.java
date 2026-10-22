package com.yangbin.yangbinaiagent.agent;

import com.yangbin.yangbinaiagent.agent.model.AgentState;
import com.yangbin.yangbinaiagent.security.UserContext;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** Bounded, cancellable loop. An interrupted remote request is never replayed automatically. */
@Data @Slf4j
public abstract class BaseAgent {
 private static final ExecutorService WORKERS=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{var t=new Thread(r,"manus-loop");t.setDaemon(true);return t;});
 private transient SseEmitter traceEmitter;
 private String name,systemPrompt,nextStepPrompt;
 private volatile AgentState state=AgentState.IDLE;
 private int currentStep,maxSteps=10;
 private ChatClient chatClient;
 private List<Message> messageList=new ArrayList<>();
 private transient volatile Thread worker;
 private transient Runnable settled=()->{};
 private final AtomicBoolean stopped=new AtomicBoolean(),notified=new AtomicBoolean();
 private transient BiConsumer<String,String> completion=(status,text)->{};
 private volatile String terminalStatus="RUNNING";
 private final StringBuilder output=new StringBuilder();
 public void onFinished(BiConsumer<String,String> listener){completion=listener;}
 private void start(String prompt){if(state!=AgentState.IDLE||prompt==null||prompt.isBlank()||prompt.length()>12000)throw new IllegalArgumentException("请输入1–12000字的消息");state=AgentState.RUNNING;messageList.add(new UserMessage(prompt));}
 public void onSettled(Runnable callback){settled=callback;}
 private void notifyFinished(){if(!notified.get()){completion.accept(terminalStatus,output.toString());notified.set(true);}}
 private void append(String text){if(output.length()<64000){if(!output.isEmpty())output.append("\n\n");output.append(text,0,Math.min(text.length(),64000-output.length()));}}
 public void cancel(){if(!stopped.compareAndSet(false,true))return;state=AgentState.ERROR;Thread running=worker;if(running!=null)running.interrupt();if(traceEmitter!=null)traceEmitter.complete();}
 public String run(String prompt){start(prompt);try{for(int i=0;i<maxSteps&&state==AgentState.RUNNING&&!stopped.get();i++){currentStep=i+1;append("Step "+currentStep+": "+step());}terminalStatus=stopped.get()?"INTERRUPTED":state==AgentState.FINISHED?"SUCCEEDED":"LIMIT_REACHED";state=AgentState.FINISHED;notifyFinished();return output.toString();}catch(Exception e){state=AgentState.ERROR;terminalStatus="FAILED";try{notifyFinished();}catch(Exception ignored){}return "执行未完成，请检查任务状态";}finally{cleanup();settled.run();}}
 protected long streamTimeoutMillis(){return 300000L;}
 protected SseEmitter streamEmitter(long timeout){return new SseEmitter(timeout);}
 private static void emit(SseEmitter emitter,Object data,boolean durable,AtomicBoolean detached)throws java.io.IOException {
  if(detached.get())return;
  try{if(data instanceof SseEmitter.SseEventBuilder event)emitter.send(event);else emitter.send(data);}
  catch(java.io.IOException|IllegalStateException disconnected){if(!durable)throw disconnected;detached.set(true);}
 }
 public SseEmitter runStream(String prompt){return runStream(prompt,false);}
 /** Durable conversations outlive a browser subscription; only explicit stop or the independent deadline cancels work. */
 public SseEmitter runStream(String prompt,boolean durable){
  start(prompt);var owner=UserContext.current();String requestTrace=org.slf4j.MDC.get("requestId");long timeout=Math.max(1,Math.min(300000,streamTimeoutMillis()));var emitter=streamEmitter(timeout);traceEmitter=emitter;var detached=new AtomicBoolean();
  Runnable disconnect=()->{if(durable)detached.set(true);else if(!notified.get())cancel();};
  emitter.onTimeout(disconnect);emitter.onError(error->disconnect.run());emitter.onCompletion(disconnect);
  Thread deadline=durable?Thread.ofVirtual().name("manus-deadline").start(()->{try{Thread.sleep(timeout);if(!notified.get())cancel();}catch(InterruptedException finished){Thread.currentThread().interrupt();}}):null;
  var future=new FutureTask<Void>(()->{worker=Thread.currentThread();try(var scope=UserContext.open(owner);var trace=org.slf4j.MDC.putCloseable("requestId",requestTrace)){
   try{for(int i=0;i<maxSteps&&state==AgentState.RUNNING&&!stopped.get();i++){
    if(Thread.currentThread().isInterrupted())throw new InterruptedException();currentStep=i+1;String text="Step "+currentStep+": "+step();
    if(stopped.get()||Thread.currentThread().isInterrupted())throw new InterruptedException();append(text);emit(emitter,text,durable,detached);
   }
   if(stopped.get())throw new InterruptedException();terminalStatus=state==AgentState.FINISHED?"SUCCEEDED":"LIMIT_REACHED";state=AgentState.FINISHED;
   if(terminalStatus.equals("LIMIT_REACHED"))emit(emitter,"执行已停止：达到步骤上限，请检查结果后继续。",durable,detached);
   notifyFinished();emit(emitter,"[DONE]",durable,detached);emitter.complete();
   }catch(Exception e){state=AgentState.ERROR;terminalStatus=stopped.get()||e instanceof InterruptedException?"INTERRUPTED":"FAILED";try{notifyFinished();}catch(Exception persistence){log.warn("agent_persistence_failed type={}",persistence.getClass().getSimpleName());}log.warn("agent_failed type={} step={}",e.getClass().getSimpleName(),currentStep);try{emit(emitter,SseEmitter.event().name("failure").data("任务未完成或状态未能保存，请查看历史状态；不会自动重试。"),durable,detached);emitter.complete();}catch(Exception ignored){}}
   finally{if(deadline!=null)deadline.interrupt();worker=null;cleanup();settled.run();}
  }return null;});
  try{WORKERS.execute(future);}catch(RejectedExecutionException e){if(deadline!=null)deadline.interrupt();terminalStatus="FAILED";state=AgentState.ERROR;try{notifyFinished();}finally{settled.run();}throw new IllegalStateException("执行队列已满，请稍后再试");}return emitter;
 }
 public abstract String step();
 protected void cleanup(){}
}
