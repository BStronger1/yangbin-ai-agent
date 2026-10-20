package com.yangbin.yangbinaiagent.controller;

import com.yangbin.yangbinaiagent.agent.YangbinManus;
import com.yangbin.yangbinaiagent.app.LoveApp;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private LoveApp loveApp;

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ChatModel appChatModel;
    @Resource private com.yangbin.yangbinaiagent.models.ModelProfileService modelProfiles;
    @Resource private com.yangbin.yangbinaiagent.models.PrivateChatModels privateChatModels;
    @Resource private com.yangbin.yangbinaiagent.chatmemory.ManusConversations manusConversations;

    @GetMapping(value="/love_app/chat/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> events(String message,String chatId,@RequestParam(defaultValue="rag") String mode) {
        var user=com.yangbin.yangbinaiagent.security.UserContext.current();String requestTrace=safeRequestId();
        return Flux.<ServerSentEvent<Object>>create(sink -> {
            var subscription=reactor.core.Disposables.swap();
            sink.onDispose(subscription);
            subscription.update(com.yangbin.yangbinaiagent.security.UserContext.call(user,()->{try(var trace=org.slf4j.MDC.putCloseable("requestId",requestTrace)){return loveApp.doChatByStream(message,chatId,mode,event -> sink.next(ServerSentEvent.builder((Object)event).event("trace").build()));}})
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .subscribe(chunk -> sink.next(ServerSentEvent.builder((Object)chunk).build()),
                    error -> {sink.next(ServerSentEvent.builder((Object)"请求未完成，请检查服务状态后重试").event("failure").build());sink.complete();},
                    () -> {sink.next(ServerSentEvent.builder((Object)"[DONE]").build());sink.complete();}));
        });
    }

    /**
     * 同步调用 AI 恋爱大师应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping("/love_app/chat/sync")
    public String doChatWithLoveAppSync(String message, String chatId) {
        return loveApp.doChat(message, chatId);
    }

    /**
     * SSE 流式调用 AI 恋爱大师应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/love_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithLoveAppSSE(String message, String chatId,
            @RequestParam(defaultValue="chat") String mode) {
        return loveApp.doChatByStream(message, chatId, mode).concatWithValues("[DONE]");
    }

    /**
     * SSE 流式调用 AI 恋爱大师应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/love_app/chat/server_sent_event")
    public Flux<ServerSentEvent<String>> doChatWithLoveAppServerSentEvent(String message, String chatId) {
        return loveApp.doChatByStream(message, chatId)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());
    }

    /**
     * SSE 流式调用 AI 恋爱大师应用
     *
     * @param message
     * @param chatId
     * @return
     */
    @GetMapping(value = "/love_app/chat/sse_emitter")
    public SseEmitter doChatWithLoveAppServerSseEmitter(String message, String chatId) {
        return relay(loveApp.doChatByStream(message,chatId),new SseEmitter(180000L));
    }

    /** Register cancellation before subscribing so early disconnects cannot orphan a subscription. */
    static SseEmitter relay(Flux<String> source,SseEmitter emitter){
        var subscription=reactor.core.Disposables.swap();
        emitter.onTimeout(()->{subscription.dispose();emitter.complete();});
        emitter.onError(error->subscription.dispose());emitter.onCompletion(subscription::dispose);
        subscription.update(source.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).subscribe(chunk->{
            try{emitter.send(chunk);}catch(IOException|IllegalStateException error){subscription.dispose();emitter.completeWithError(new IllegalStateException("消息传输已中断"));}
        },error->{try{emitter.send(SseEmitter.event().name("failure").data("请求未完成，请查看任务状态后重试"));}catch(Exception ignored){}finally{emitter.complete();}},emitter::complete));
        return emitter;
    }
    private static String safeRequestId(){String id=org.slf4j.MDC.get("requestId");return id!=null&&id.matches("[a-f0-9]{32}")?id:"";}
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public org.springframework.http.ResponseEntity<?> invalid(IllegalArgumentException error){return org.springframework.http.ResponseEntity.badRequest().body(java.util.Map.of("message","请求参数无效，请检查消息、模型或会话编号"));}
    @org.springframework.web.bind.annotation.ExceptionHandler(com.yangbin.yangbinaiagent.governance.ModelGovernance.Denied.class)
    public org.springframework.http.ResponseEntity<?> limited(com.yangbin.yangbinaiagent.governance.ModelGovernance.Denied error){return org.springframework.http.ResponseEntity.status(429).body(java.util.Map.of("message","模型额度不足、并发已满或服务熔断中，请稍后重试"));}
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalStateException.class)
    public org.springframework.http.ResponseEntity<?> conflict(IllegalStateException error){return org.springframework.http.ResponseEntity.status(409).body(java.util.Map.of("message","任务状态冲突或服务暂不可用，请检查任务状态后重试"));}

    /**
     * 流式调用 Manus 超级智能体
     *
     * @param message
     * @return
     */
    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message,@RequestParam(required=false) String modelProfileId,@RequestParam(required=false) String chatId,@RequestParam(required=false) String requestId)throws Exception {
        var selected=modelProfiles.resolve(modelProfileId);ChatModel model=selected==null?appChatModel:privateChatModels.create(selected);
        var owner=com.yangbin.yangbinaiagent.security.UserContext.current();
        String cid=chatId==null?java.util.UUID.randomUUID().toString():chatId,rid=requestId==null?java.util.UUID.randomUUID().toString():requestId;
        var reserved=manusConversations.reserve(cid,rid,message,modelProfileId);
        if(reserved.existing()){
            var turn=reserved.conversation().turns().stream().filter(t->t.id().equals(rid)).findFirst().orElseThrow();
            var emitter=new SseEmitter(300000L);
            if(turn.status().equals("RUNNING"))emitter.send(SseEmitter.event().name("failure").data("同一任务仍在执行，请查看对话历史，不会重复发送。"));
            else {if(!turn.answer().isBlank())emitter.send(turn.answer());if(!turn.status().equals("SUCCEEDED"))emitter.send(SseEmitter.event().name("failure").data("历史任务状态："+turn.status()));emitter.send("[DONE]");}emitter.complete();return emitter;
        }
        var agent=new YangbinManus(com.yangbin.yangbinaiagent.security.ToolAccess.forUser(allTools,owner),model);
        agent.setMessageList(new java.util.ArrayList<>(reserved.context()));
        agent.onFinished((status,text)->{try(var scope=com.yangbin.yangbinaiagent.security.UserContext.open(owner)){try{manusConversations.complete(cid,rid,status,text);}catch(Exception e){throw new IllegalStateException("Unable to persist conversation");}}});
        agent.onSettled(()->{try(var scope=com.yangbin.yangbinaiagent.security.UserContext.open(owner)){manusConversations.release(cid);}});
        manusConversations.attach(cid,rid,agent);return agent.runStream(message);
    }
}
