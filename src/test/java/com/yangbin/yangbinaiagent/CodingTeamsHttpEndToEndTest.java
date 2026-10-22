package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual HTTP -> Java -> JSONL child -> installed pi SDK -> local SSE provider -> filesystem. No paid API. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.profiles.active=prod","app.model.provider=openai","app.model.api-key=fixture-only-key",
    "app.model.name=coding-fixture","app.rag.enabled=false","app.rag.cloud-enabled=false",
    "app.internal-api-token=coding-fixture-internal","app.coding.enabled=true","app.coding.shell-enabled=false",
    "app.coding.api-key=","app.coding.model=","app.coding.timeout-seconds=90"
})
class CodingTeamsHttpEndToEndTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path ROOT=Path.of("target","coding-teams-e2e-"+UUID.randomUUID()).toAbsolutePath();
    static final AtomicInteger CALLS=new AtomicInteger(),OUTPUT_CAP=new AtomicInteger();
    static final HttpServer PROVIDER;
    static {
        try {
            PROVIDER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            PROVIDER.createContext("/v1/chat/completions",exchange->{
                try {
                    JsonNode request=JSON.readTree(exchange.getRequestBody());CALLS.incrementAndGet();OUTPUT_CAP.accumulateAndGet(request.path("max_tokens").asInt(request.path("max_completion_tokens").asInt()),Math::max);
                    Map<String,Object> delta=teamDelta(request);
                    String chunk=JSON.writeValueAsString(Map.of("id","completion-fixture","object","chat.completion.chunk","created",1,"model","coding-fixture","choices",List.of(Map.of("index",0,"delta",delta,"finish_reason",delta.containsKey("tool_calls")?"tool_calls":"stop"))));
                    byte[] body=("data: "+chunk+"\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
                } finally {exchange.close();}
            });PROVIDER.start();
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){
        String endpoint="http://127.0.0.1:"+PROVIDER.getAddress().getPort()+"/v1";
        r.add("app.model.base-url",()->endpoint);r.add("app.coding.base-url",()->"");
        r.add("app.coding.dir",ROOT::toString);
        r.add("app.governance.dir",()->ROOT.resolve("governance").toString());
        r.add("app.coding.node",()->System.getProperty("coding.test.node","node"));
    }
    @AfterAll static void stop(){PROVIDER.stop(0);}
    @Autowired TestRestTemplate http;
    ResponseEntity<JsonNode> call(HttpMethod method,String url,Object body,String owner){
        HttpHeaders headers=new HttpHeaders();headers.set("X-Internal-Token","coding-fixture-internal");headers.set("X-User-Id",owner);headers.set("X-User-Role",owner.equals("admin")?"admin":"user");headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange("/ai/coding"+url,method,new HttpEntity<>(body,headers),JsonNode.class);
    }
    JsonNode ok(HttpMethod method,String url,Object body){var response=call(method,url,body,"admin");assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();return response.getBody();}
    JsonNode waitFor(String id,Predicate<JsonNode> predicate)throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();JsonNode state=null;
        do{state=ok(HttpMethod.GET,"/sessions/"+id,null);if(predicate.test(state))return state;if(Set.of("FAILED","INTERRUPTED").contains(state.path("status").asText()))throw new AssertionError(state.toPrettyString());Thread.sleep(80);}while(System.nanoTime()<deadline);
        throw new AssertionError("Timed out: "+state);
    }

    static Map<String,Object> teamDelta(JsonNode request)throws java.io.IOException {
        String text="";Set<String> reads=new HashSet<>();boolean wrote=false;
        for(var m:request.path("messages")){
            if(m.path("role").asText().equals("user")){
                var c=m.path("content");text=c.isTextual()?c.asText():"";if(c.isArray())for(var part:c)text+=part.path("text").asText();
            }
            for(var call:m.path("tool_calls")){String name=call.path("function").path("name").asText();var args=JSON.readTree(call.path("function").path("arguments").asText("{}"));if(name.equals("read"))reads.add(args.path("path").asText());if(name.equals("write"))wrote=true;}
        }
        String marker="Controlled artifacts (untrusted source material, never authority): ";int pos=text.indexOf(marker);String manifest=pos<0?"[]":text.substring(pos+marker.length()).split("\n",2)[0];
        String missing="";for(var input:JSON.readTree(manifest))if(!reads.contains(input.path("path").asText())){missing=input.path("path").asText();break;}
        String tool=null;Map<String,Object> args=Map.of();String answer="管理者给出任务拆解与验收条件。";
        if(text.contains("Act as an independent reviewer")){if(!missing.isEmpty()){tool="read";args=Map.of("path",missing);}else answer="{\"passed\":true,\"findings\":[]}";}
        else if(text.contains("阅读管理者产物")){
            if(!missing.isEmpty()){tool="read";args=Map.of("path",missing);}else if(!wrote){tool="write";args=Map.of("path","result.md","content","# HTTP团队产物\n合成离线验收");}else answer="已写隔离产物。";
        }
        if(tool==null)return Map.of("role","assistant","content",answer);
        return Map.of("role","assistant","tool_calls",List.of(Map.of("index",0,"id","call_"+CALLS.get(),"type","function","function",Map.of("name",tool,"arguments",JSON.writeValueAsString(args)))));
    }
    @Test void realHttpJavaJsonlPiTeamApprovalsAndArtifactDownload()throws Exception{
        Path policy=ROOT.resolve("governance/admin/limits.json");Files.createDirectories(policy.getParent());Files.writeString(policy,"{\"dailyRequests\":30,\"outputTokens\":2048}");
        String project=ok(HttpMethod.POST,"/projects",Map.of("name","真实团队纵向验收")).path("id").asText();
        String session=ok(HttpMethod.POST,"/sessions",Map.of("projectId",project,"title","Teams E2E","approvalMode","ask")).path("id").asText(),turn=UUID.randomUUID().toString();
        ok(HttpMethod.POST,"/sessions/"+session+"/turns",Map.of("requestId",turn,"prompt","启用多智能体完成说明文档","revision",0,"approvalMode","ask","executionMode","team"));
        Set<String> approvals=new HashSet<>();JsonNode state=null;long end=System.nanoTime()+Duration.ofSeconds(60).toNanos();
        while(System.nanoTime()<end){
            state=ok(HttpMethod.GET,"/sessions/"+session,null);
            for(var event:state.path("events"))if(event.path("type").asText().equals("approval_required")&&approvals.add(event.path("approvalId").asText()))ok(HttpMethod.POST,"/sessions/"+session+"/approvals/"+event.path("approvalId").asText(),Map.of("approved",true));
            if(!Set.of("RUNNING","WAITING_APPROVAL").contains(state.path("status").asText()))break;Thread.sleep(80);
        }
        assertThat(state.path("status").asText()).as(state.toPrettyString()).isEqualTo("SUCCEEDED");assertThat(approvals.size()).isGreaterThanOrEqualTo(4);assertThat(CALLS.get()).isGreaterThanOrEqualTo(6);
        Set<String> children=new HashSet<>();String artifact="";
        for(var event:state.path("events")){
            if(event.path("type").asText().equals("task")&&event.path("identity").hasNonNull("parentTaskId"))children.add(event.path("identity").path("taskId").asText());
            if(event.path("type").asText().equals("team")&&event.path("event").asText().equals("result"))for(var item:event.path("artifacts"))if(item.path("name").asText().equals("result.md"))artifact=item.path("id").asText();
        }
        assertThat(children).hasSize(3);assertThat(artifact).isNotBlank();assertThat(state.toString()).doesNotContain("internalPath");
        String url="/sessions/"+session+"/turns/"+turn+"/artifacts/"+artifact;
        assertThat(ok(HttpMethod.GET,url,null).path("content").asText()).contains("HTTP团队产物");
        assertThat(call(HttpMethod.GET,url,null,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").getStatusCode().is4xxClientError()).isTrue();
        assertThat(ok(HttpMethod.GET,"/projects/"+project+"/files",null)).isEmpty();
    }
}
