package com.yangbin.yangbinaiagent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

/** Metadata only: never log prompts, responses, tool arguments, or exception bodies. */
@Slf4j
public class MyLoggerAdvisor implements CallAdvisor, StreamAdvisor {
    @Override public String getName() { return getClass().getSimpleName(); }
    @Override public int getOrder() { return 0; }
    private String requestId() {
        String id=MDC.get("requestId");
        return id!=null&&id.matches("[a-f0-9]{32}")?id:"unlinked";
    }
    private void completed(String trace,String status,long started) {
        log.info("event=model_exchange requestId={} status={} durationMs={}", trace,status,(System.nanoTime()-started)/1000000);
    }
    @Override public ChatClientResponse adviseCall(ChatClientRequest request,CallAdvisorChain chain) {
        long started=System.nanoTime();String trace=requestId();String status="failed";
        try {var result=chain.nextCall(request);status="completed";return result;}
        finally {completed(trace,status,started);}
    }
    @Override public Flux<ChatClientResponse> adviseStream(ChatClientRequest request,StreamAdvisorChain chain) {
        String trace=requestId();
        return Flux.defer(()->{
            long started=System.nanoTime();
            return chain.nextStream(request).doFinally(signal->completed(trace,switch(signal){
                case ON_COMPLETE -> "completed";case CANCEL -> "cancelled";default -> "failed";
            },started));
        });
    }
}
