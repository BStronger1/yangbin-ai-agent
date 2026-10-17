package com.yangbin.yangbinaiagent.models;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
/** Allows a fresh installation to start before an administrator configures the shared model. */
public final class UnconfiguredChatModel implements ChatModel {
    private static IllegalStateException unavailable(){return new IllegalStateException("系统默认模型尚未配置，请添加并选择个人模型");}
    public ChatResponse call(Prompt prompt){throw unavailable();}
    public Flux<ChatResponse> stream(Prompt prompt){return Flux.error(unavailable());}
}
