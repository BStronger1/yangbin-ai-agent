package com.yangbin.yangbinaiagent.controller;

import com.yangbin.yangbinaiagent.security.UserContext;
import com.yangbin.yangbinaiagent.work.PartnerChatService;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;

/** A workspace-scoped projection over the existing durable partner runtime. Subscriptions never own jobs. */
@RestController
@RequestMapping("/ai/assistant/partners/{partnerId}")
public class AssistantController {
    private final PartnerChatService service;
    private final Semaphore subscriptions = new Semaphore(32);
    public AssistantController(PartnerChatService service) { this.service=service; }
    @GetMapping("/conversations")
    public List<PartnerChatService.Summary> list(@PathVariable String partnerId,@RequestParam String mode)throws Exception {
        return service.list(partnerId,mode);
    }
    @GetMapping("/conversations/{id}/page")
    public PartnerChatService.Page page(@PathVariable String partnerId,@PathVariable String id,@RequestParam String mode,
            @RequestParam(required=false) Integer before,@RequestParam(defaultValue="8") int limit)throws Exception {
        return service.page(partnerId,id,before,limit,mode);
    }
    @PostMapping("/jobs")
    public ResponseEntity<PartnerChatService.Page> submit(@PathVariable String partnerId,
            @RequestBody PartnerChatService.Input input,@RequestParam String mode)throws Exception {
        if(input==null)throw new IllegalArgumentException("请输入消息");
        PartnerChatService.assistantMode(mode);
        PartnerChatService.assistantMode(input.mode());
        if(!mode.equals(input.mode()))throw new IllegalArgumentException("请求工作空间不一致");
        service.submit(partnerId,input);
        return ResponseEntity.accepted().body(service.page(partnerId,input.conversationId(),null,8,input.mode()));
    }
    @PostMapping("/conversations/{id}/stop")
    public PartnerChatService.Page stop(@PathVariable String partnerId,@PathVariable String id,@RequestParam String mode)throws Exception {
        return service.cancel(partnerId,id,mode);
    }
    @GetMapping(value="/conversations/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> events(@PathVariable String partnerId,@PathVariable String id,@RequestParam String mode)throws Exception {
        service.inMode(partnerId,id,mode); // Reject wrong account/workspace before opening a stream.
        var owner=UserContext.current();
        return Flux.defer(()->{
            if(!subscriptions.tryAcquire())return Flux.error(new IllegalStateException("订阅连接已满，请稍后刷新"));
            return Flux.interval(Duration.ZERO,Duration.ofSeconds(1))
                .publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .map(tick->{try(var ignored=UserContext.open(owner)){
                    try{return service.page(partnerId,id,null,8,mode);}catch(Exception e){throw new IllegalStateException("无法读取任务状态");}
                }})
                .distinctUntilChanged()
                .concatMap(snapshot->{var state=ServerSentEvent.builder((Object)snapshot).event("state").build();
                    return snapshot.turns().getLast().status().equals("RUNNING")?Flux.just(state):Flux.just(state,ServerSentEvent.builder((Object)"[DONE]").event("done").build());})
                .takeUntil(event->"done".equals(event.event()))
                .take(Duration.ofMinutes(5)) // Subscription lifetime only; browser may reconnect to the same durable record.
                .doFinally(signal->subscriptions.release());
        });
    }
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.web.bind.MissingServletRequestParameterException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<?> invalid(Exception e){return ResponseEntity.badRequest().body(Map.of("message",e instanceof IllegalArgumentException?e.getMessage():"请求参数或消息格式无效"));}
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> conflict(IllegalStateException e){return ResponseEntity.status(409).body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(Exception.class) public ResponseEntity<?> failure(Exception e){return ResponseEntity.status(503).body(Map.of("message","任务操作未完成，请刷新确认状态；不会自动重试。"));}
}
