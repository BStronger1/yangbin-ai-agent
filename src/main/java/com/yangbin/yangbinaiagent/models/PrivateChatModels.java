package com.yangbin.yangbinaiagent.models;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.*;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import reactor.core.publisher.Flux;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import java.util.concurrent.ConcurrentHashMap;
import java.net.*;
import java.util.*;

/** Per-call transports keep BYOK credentials out of shared model instances and close every connection. */
@Component
public class PrivateChatModels {
    private static final ObjectMapper JSON=new ObjectMapper();
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.yangbin.yangbinaiagent.governance.ModelGovernance governance;
    public ChatModel create(ModelProfileService.Resolved selected) {
        Map<String,String> reasoningByToolCall=new ConcurrentHashMap<>();
        boolean reasoningProvider=Set.of("deepseek","kimi").contains(selected.snapshot().provider());
        ChatModel model = new ChatModel(){
            @Override public ChatOptions getDefaultOptions(){return "anthropic-messages".equals(selected.snapshot().api())?AnthropicChatOptions.builder().model(selected.snapshot().model()).maxTokens(4096).build():OpenAiChatOptions.builder().model(selected.snapshot().model()).build();}
            @Override public ChatResponse call(Prompt prompt){
                try(CloseableHttpClient http=transport(selected.allowPrivate())){
                    var explicitUsage=new java.util.concurrent.atomic.AtomicBoolean(false);
                    URI endpoint=URI.create(ModelEndpointPolicy.normalize(selected.snapshot().baseUrl(),selected.allowPrivate()));
                    String origin=new URI(endpoint.getScheme(),null,endpoint.getHost(),endpoint.getPort(),null,null,null).toString();
                    String prefix=Objects.toString(endpoint.getRawPath(),"").replaceAll("/+$","");
                    var requestFactory=new HttpComponentsClientHttpRequestFactory(http);requestFactory.setConnectTimeout(15000);requestFactory.setReadTimeout(120000);
                    var rest=RestClient.builder().requestFactory(requestFactory).requestInterceptor((request,body,execution)->{
                        URI destination=request.getURI();if(!Objects.equals(endpoint.getScheme(),destination.getScheme())||!Objects.equals(endpoint.getHost(),destination.getHost())||endpoint.getPort()!=destination.getPort())throw new IOException("Model destination changed");
                        byte[] outgoing=body;
                        if(reasoningProvider){
                            JsonNode payload=JSON.readTree(body);
                            for(JsonNode message:payload.path("messages"))if("assistant".equals(message.path("role").asText())&&message instanceof ObjectNode object){
                                String reasoning="";for(JsonNode tool:message.path("tool_calls")){String saved=reasoningByToolCall.get(tool.path("id").asText());if(saved!=null){reasoning=saved;break;}}
                                object.put("reasoning_content",reasoning);
                            }
                            outgoing=JSON.writeValueAsBytes(payload);request.getHeaders().setContentLength(outgoing.length);
                        }
                        ClientHttpResponse response=execution.execute(request,outgoing);
                        try{
                            byte[] raw=response.getBody().readNBytes(4*1024*1024+1);if(raw.length>4*1024*1024)throw new IOException("Model response exceeds limit");
                            JsonNode usagePayload=JSON.readTree(raw); JsonNode rawUsage=usagePayload.path("usage");
                            explicitUsage.set((rawUsage.path("prompt_tokens").isIntegralNumber()&&rawUsage.path("completion_tokens").isIntegralNumber())||(rawUsage.path("input_tokens").isIntegralNumber()&&rawUsage.path("output_tokens").isIntegralNumber()));
                            byte[] safe=SecretRedactor.redact(new String(raw,StandardCharsets.UTF_8),selected.apiKey()).getBytes(StandardCharsets.UTF_8);
                            if(reasoningProvider&&response.getStatusCode().is2xxSuccessful()){
                                JsonNode payload=JSON.readTree(safe);for(JsonNode choice:payload.path("choices")){JsonNode message=choice.path("message");String reasoning=message.path("reasoning_content").asText("");if(reasoning.length()>65536)throw new IOException("Model reasoning exceeds limit");for(JsonNode tool:message.path("tool_calls"))reasoningByToolCall.put(tool.path("id").asText(),reasoning);}
                            }
                            HttpHeaders headers=new HttpHeaders();headers.putAll(response.getHeaders());headers.setContentLength(safe.length);
                            return new ClientHttpResponse(){
                                public HttpStatusCode getStatusCode()throws IOException{return response.getStatusCode();}
                                public String getStatusText()throws IOException{return response.getStatusText();}
                                public HttpHeaders getHeaders(){return headers;}
                                public java.io.InputStream getBody(){return new ByteArrayInputStream(safe);}
                                public void close(){response.close();}
                            };
                        }catch(Exception e){response.close();if(e instanceof IOException io)throw io;throw new IOException("Invalid model response");}
                    });
                    var noRetry=RetryTemplate.builder().maxAttempts(1).build();
                    ResponseErrorHandler errors=new ResponseErrorHandler(){
                        public boolean hasError(ClientHttpResponse response)throws IOException{return !response.getStatusCode().is2xxSuccessful();}
                        public void handleError(ClientHttpResponse response)throws IOException{throw new IOException("模型服务拒绝了请求，请检查模型配置或额度");}
                    };
                    ChatModel delegate;
                    if("anthropic-messages".equals(selected.snapshot().api())){
                        var api=AnthropicApi.builder().baseUrl(origin).completionsPath(prefix+"/v1/messages").apiKey(selected.apiKey()).restClientBuilder(rest).responseErrorHandler(errors).build();
                        delegate=AnthropicChatModel.builder().anthropicApi(api).defaultOptions((AnthropicChatOptions)getDefaultOptions()).retryTemplate(noRetry).build();
                    }else{
                        var api=OpenAiApi.builder().baseUrl(origin).completionsPath(prefix+"/chat/completions").apiKey(selected.apiKey()).restClientBuilder(rest).responseErrorHandler(errors).build();
                        delegate=OpenAiChatModel.builder().openAiApi(api).defaultOptions((OpenAiChatOptions)getDefaultOptions()).retryTemplate(noRetry).build();
                    }
                    return sanitize(delegate.call(prompt),selected.apiKey(),explicitUsage.get());
                }catch(Exception e){throw new IllegalStateException("个人模型请求未完成，请检查 API Key、地址、模型名称及额度");}
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt){return Flux.defer(()->Flux.just(call(prompt)));}
            @Override public String toString(){return "PrivateChatModel["+selected.snapshot().profileId()+"]";}
        };
        return governance==null?model:governance.govern(model,selected.snapshot().provider(),selected.snapshot().model());
    }
    private static CloseableHttpClient transport(boolean allowPrivate){
        var resolver=new DnsResolver(){
            public InetAddress[] resolve(String host)throws UnknownHostException{return ModelEndpointPolicy.resolve(host,allowPrivate);}
            public String resolveCanonicalHostname(String host)throws UnknownHostException{ModelEndpointPolicy.resolve(host,allowPrivate);return host;}
        };
        var manager=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver).build();
        return HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofSeconds(120)).setConnectionRequestTimeout(Timeout.ofSeconds(15)).build()).build();
    }
    private static ChatResponse sanitize(ChatResponse response,String key,boolean explicitUsage){
        // Anthropic 1.0 exposes individual content blocks as generations; Manus expects one assistant turn.
        var calls=new ArrayList<AssistantMessage.ToolCall>();var text=new ArrayList<String>();
        for(var generation:response.getResults()){var message=generation.getOutput();String part=redact(message.getText(),key);if(!part.isBlank())text.add(part);for(var t:message.getToolCalls())calls.add(new AssistantMessage.ToolCall(redact(t.id(),key),redact(t.type(),key),redact(t.name(),key),redact(t.arguments(),key)));}
        var usage=response.getMetadata().getUsage();
        var metadata=org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().model(redact(response.getMetadata().getModel(),key));
        if(explicitUsage && usage!=null && !(usage instanceof org.springframework.ai.chat.metadata.EmptyUsage)) metadata.usage(new org.springframework.ai.chat.metadata.DefaultUsage(usage.getPromptTokens(),usage.getCompletionTokens(),usage.getTotalTokens()));
        return new ChatResponse(List.of(new Generation(new AssistantMessage(String.join("\n",text),Map.of(),calls))),metadata.build());
    }
    private static String redact(String value,String key){return SecretRedactor.redact(value,key);}
}
