package com.yangbin.yangbinaiagent.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class ChatModelConfig {
    @Bean("appChatModel")
    @Primary
    @ConditionalOnProperty(name = "app.model.provider", havingValue = "openai")
    ChatModel compatibleChatModel(@Value("${app.model.base-url}") String baseUrl,
                                 @Value("${app.model.api-key}") String apiKey,
                                 @Value("${app.model.name}") String model) {
        if (apiKey.isBlank()) throw new IllegalArgumentException("MODEL_API_KEY is required");
        String normalized = baseUrl.replaceAll("/+$", "");
        if (normalized.endsWith("/v1")) normalized = normalized.substring(0, normalized.length() - 3);
        OpenAiApi api = OpenAiApi.builder().baseUrl(normalized).apiKey(apiKey).build();
        return OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(model).temperature(0.7).build()).build();
    }

    @Bean("appChatModel")
    @Primary
    @ConditionalOnProperty(name = "app.model.provider", havingValue = "dashscope", matchIfMissing = true)
    ChatModel dashscopeAppChatModel(@Qualifier("dashscopeChatModel") ChatModel model) {
        return model;
    }
}
