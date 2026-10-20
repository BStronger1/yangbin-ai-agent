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
                                 @Value("${app.model.name}") String model,com.yangbin.yangbinaiagent.governance.ModelGovernance governance) {
        if (apiKey.isBlank()) return new com.yangbin.yangbinaiagent.models.UnconfiguredChatModel();
        String normalized = baseUrl.replaceAll("/+$", "");
        if (normalized.endsWith("/v1")) normalized = normalized.substring(0, normalized.length() - 3);
        OpenAiApi api = OpenAiApi.builder().baseUrl(normalized).apiKey(apiKey).responseErrorHandler(safeErrors()).build();
        return governance.govern(OpenAiChatModel.builder().openAiApi(api).retryTemplate(org.springframework.retry.support.RetryTemplate.builder().maxAttempts(1).build())
                .defaultOptions(OpenAiChatOptions.builder().model(model).temperature(0.7).build()).build(),"system-openai",model);
    }

    @Bean("appChatModel")
    @Primary
    @ConditionalOnProperty(name = "app.model.provider", havingValue = "dashscope", matchIfMissing = true)
    ChatModel dashscopeAppChatModel(@Qualifier("dashscopeChatModel") ChatModel model, @Value("${spring.ai.dashscope.api-key:}") String key,com.yangbin.yangbinaiagent.governance.ModelGovernance governance) {
        if(key.isBlank() || key.equals("development-placeholder"))return new com.yangbin.yangbinaiagent.models.UnconfiguredChatModel();
        // The auto-configured SDK otherwise retries inside one admission/usage record.
        if(model instanceof com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel dashscope){
            dashscope.retryTemplate.setRetryPolicy(new org.springframework.retry.policy.NeverRetryPolicy());
            dashscope.retryTemplate.setListeners(new org.springframework.retry.RetryListener[0]);
        }
        // This SDK's existing API object cannot replace its response handler after construction.
        // Its default retry logger includes exception bodies. Our governance layer emits safe status only.
        org.springframework.boot.logging.LoggingSystem.get(ChatModelConfig.class.getClassLoader()).setLogLevel("org.springframework.ai.retry.RetryUtils",org.springframework.boot.logging.LogLevel.OFF);
        return governance.govern(model,"system-dashscope","system");
    }
    static org.springframework.web.client.ResponseErrorHandler safeErrors(){return new org.springframework.web.client.ResponseErrorHandler(){
        public boolean hasError(org.springframework.http.client.ClientHttpResponse response)throws java.io.IOException{return !response.getStatusCode().is2xxSuccessful();}
        public void handleError(org.springframework.http.client.ClientHttpResponse response)throws java.io.IOException{throw new java.io.IOException("模型服务拒绝请求，请检查配置和额度");}
    };}

}
