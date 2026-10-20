package com.yangbin.yangbinaiagent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.yangbin.yangbinaiagent.advisor.MyLoggerAdvisor;
import com.yangbin.yangbinaiagent.tools.TracedTools;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import java.util.ArrayList;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentFreeLoggingTest {
    @Test void interruptedWorkerCannotStartAnotherTool() {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        ToolCallback callback=new ToolCallback(){
            public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("side_effect").description("test").inputSchema("{}").build();}
            public String call(String input){calls.incrementAndGet();return "executed";}
        };
        var events=new ArrayList<Map<String,Object>>();var wrapped=TracedTools.wrap(new ToolCallback[]{callback},events::add)[0];
        Thread.currentThread().interrupt();
        try{
            assertThatThrownBy(()->wrapped.call("private-input")).isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(calls).hasValue(0);assertThat(events).isEmpty();assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }finally{Thread.interrupted();}
    }

    @Test void modelFailuresAndCompletionOnlyLogMetadata() {
        Logger logger=(Logger)LoggerFactory.getLogger(MyLoggerAdvisor.class);
        var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        MDC.put("requestId","a".repeat(32));
        try {
            var advisor=new MyLoggerAdvisor();var chain=mock(CallAdvisorChain.class);
            when(chain.nextCall(null)).thenThrow(new IllegalStateException("private-api-key-private-prompt"));
            assertThatThrownBy(()->advisor.adviseCall(null,chain)).isInstanceOf(IllegalStateException.class);
            var stream=mock(StreamAdvisorChain.class);when(stream.nextStream(null)).thenReturn(Flux.empty());
            advisor.adviseStream(null,stream).blockLast();
            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage)).allSatisfy(text->{
                assertThat(text).contains("event=model_exchange","requestId="+"a".repeat(32),"durationMs=").doesNotContain("private");
            });
            assertThat(appender.list).allSatisfy(event->assertThat(event.getThrowableProxy()).isNull());
        } finally {MDC.clear();logger.detachAppender(appender);}
    }
    @Test void toolTelemetryNeverLogsArgumentsResultOrException() {
        Logger logger=(Logger)LoggerFactory.getLogger(TracedTools.class);
        var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        try {
            ToolCallback callback=new ToolCallback(){
                public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("safe_tool").description("test").inputSchema("{}").build();}
                public String call(String input){if(input.contains("fail"))throw new IllegalArgumentException("private-error");return "private-result";}
            };
            var events=new ArrayList<Map<String,Object>>();var wrapped=TracedTools.wrap(new ToolCallback[]{callback},events::add)[0];
            assertThat(wrapped.call("private-input")).isEqualTo("private-result");
            assertThatThrownBy(()->wrapped.call("private-fail")).isInstanceOf(IllegalArgumentException.class);
            assertThat(appender.list).hasSize(2).allSatisfy(event->{
                assertThat(event.getFormattedMessage()).contains("event=tool_complete","durationMs=").doesNotContain("private");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(events.get(1).get("result")).isEqualTo("private-result"); // Explicit user-facing result remains functional.
        }finally{logger.detachAppender(appender);}
    }
}
