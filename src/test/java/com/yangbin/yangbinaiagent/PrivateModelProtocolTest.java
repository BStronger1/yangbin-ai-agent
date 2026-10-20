package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import com.yangbin.yangbinaiagent.agent.YangbinManus;
import com.yangbin.yangbinaiagent.models.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class PrivateModelProtocolTest {
    final ObjectMapper json=new ObjectMapper();HttpServer server;String origin;List<JsonNode> requests=new ArrayList<>();AtomicInteger tools=new AtomicInteger(),stolen=new AtomicInteger();String credential="fixture-\\key-\"quoted\"";
    @BeforeEach void setup()throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.start();origin="http://127.0.0.1:"+server.getAddress().getPort();}
    @AfterEach void close(){server.stop(0);}
    ModelProfileService.Resolved selected(String provider,String path){return new ModelProfileService.Resolved(new ModelProfileService.Snapshot("test-id","test",provider,origin+path,"fixture-model",provider.equals("anthropic")?"anthropic-messages":"openai-completions"),credential,true);}
    void reply(HttpExchange exchange,int status,Object data)throws java.io.IOException{byte[] bytes=json.writeValueAsBytes(data);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}
    ToolCallback tool(){return new ToolCallback(){public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("echoWitness").description("Return test evidence").inputSchema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}").build();}public String call(String input){assertThat(input).doesNotContain(credential).contains("[REDACTED]");tools.incrementAndGet();return "tool-evidence";}};}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"openai","deepseek","kimi"}) void openAiCompatiblePrefixPreservesManusToolLoopAndRedactsEchoedKey(String provider)throws Exception{
        server.createContext("/gateway/provider/chat/completions",e->{try{
            assertThat(e.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer "+credential);JsonNode r=json.readTree(e.getRequestBody());requests.add(r);assertThat(r.path("model").asText()).isEqualTo("fixture-model");assertThat(r.has("temperature")).isFalse();
            if(requests.size()==2&&!provider.equals("openai"))assertThat(r.path("messages").findValuesAsText("reasoning_content")).contains("reasoning-proof");
            Map<String,Object> message=requests.size()==1?Map.of("role","assistant","content","","reasoning_content","reasoning-proof", "tool_calls",List.of(Map.of("id","call-1","type","function","function",Map.of("name","echoWitness","arguments",json.writeValueAsString(Map.of("text",credential)))))):Map.of("role","assistant","content","finished "+credential);
            reply(e,200,Map.of("id","completion","object","chat.completion","created",1,"model","fixture-model","choices",List.of(Map.of("index",0,"message",message,"finish_reason",requests.size()==1?"tool_calls":"stop"))));
        }catch(Throwable failure){reply(e,500,Map.of("error","fixture failure"));throw new RuntimeException(failure);}});
        String result=new YangbinManus(new ToolCallback[]{tool()},new PrivateChatModels().create(selected(provider,"/gateway/provider"))).run("use the tool then answer");
        assertThat(result).contains("tool-evidence","finished [REDACTED]").doesNotContain(credential,"执行错误");assertThat(tools).hasValue(1);assertThat(requests).hasSize(2);assertThat(requests.get(1).toString()).contains("tool-evidence");
    }
    @Test void nativeAnthropicMessagesPreservesManusToolLoopAndProtocolHeaders()throws Exception{
        server.createContext("/anthropic-gateway/v1/messages",e->{try{
            assertThat(e.getRequestHeaders().getFirst("x-api-key")).isEqualTo(credential);assertThat(e.getRequestHeaders().getFirst("anthropic-version")).isNotBlank();JsonNode r=json.readTree(e.getRequestBody());requests.add(r);assertThat(r.path("model").asText()).isEqualTo("fixture-model");assertThat(r.path("max_tokens").asInt()).isPositive();assertThat(r.has("temperature")).isFalse();
            var content=requests.size()==1?List.of(Map.of("type","tool_use","id","tool-1","name","echoWitness","input",Map.of("text",credential))):List.of(Map.of("type","text","text","claude finished "+credential));
            reply(e,200,Map.of("id","msg-test","type","message","role","assistant","model","fixture-model","content",content,"stop_reason",requests.size()==1?"tool_use":"end_turn","usage",Map.of("input_tokens",10,"output_tokens",5)));
        }catch(Throwable failure){reply(e,500,Map.of("error","fixture failure"));throw new RuntimeException(failure);}});
        String result=new YangbinManus(new ToolCallback[]{tool()},new PrivateChatModels().create(selected("anthropic","/anthropic-gateway"))).run("use native tool");
        assertThat(result).contains("tool-evidence","claude finished [REDACTED]").doesNotContain(credential,"执行错误");assertThat(tools).hasValue(1);assertThat(requests).hasSize(2);assertThat(requests.get(1).toString()).contains("tool_result","tool-evidence");
    }
    @Test void refusesRedirectWithoutSendingCredentialToRedirectTarget()throws Exception{
        server.createContext("/redirect/chat/completions",e->{e.getResponseHeaders().set("Location",origin+"/stolen");e.sendResponseHeaders(302,-1);e.close();});server.createContext("/stolen",e->{stolen.incrementAndGet();reply(e,200,Map.of());});
        assertThatThrownBy(()->new PrivateChatModels().create(selected("openai","/redirect")).call(new Prompt("hello"))).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(credential);assertThat(stolen).hasValue(0);
    }
    @Test void providerErrorBodyIsNotExposedThroughExceptionOrCause()throws Exception{
        server.createContext("/error/chat/completions",e->reply(e,401,Map.of("error",Map.of("message","rejected "+credential))));
        assertThatThrownBy(()->new PrivateChatModels().create(selected("openai","/error")).call(new Prompt("hello"))).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(credential).hasNoCause();
    }
    @Test void usageMetadataIsPreservedOnlyWhenProviderActuallyReportsIt()throws Exception{
        server.createContext("/usage/chat/completions",e->{int n=tools.incrementAndGet();var payload=new java.util.HashMap<String,Object>(Map.of("id","usage","object","chat.completion","created",1,"model","fixture-model","choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content","ok"),"finish_reason","stop"))));if(n==1)payload.put("usage",Map.of("prompt_tokens",12,"completion_tokens",4,"total_tokens",16));reply(e,200,payload);});
        var model=new PrivateChatModels().create(selected("openai","/usage"));var known=model.call(new Prompt("hello"));assertThat(known.getMetadata().getUsage().getTotalTokens()).isEqualTo(16);var unknown=model.call(new Prompt("hello"));assertThat(unknown.getMetadata().getUsage()).isInstanceOf(org.springframework.ai.chat.metadata.EmptyUsage.class);
    }
    @Test void unconfiguredSharedModelFailsClearlyWithoutExternalRequest(){assertThatThrownBy(()->new UnconfiguredChatModel().call(new Prompt("hello"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("个人模型");}
}
