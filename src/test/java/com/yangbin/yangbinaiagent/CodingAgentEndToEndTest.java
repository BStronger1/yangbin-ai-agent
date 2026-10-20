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
class CodingAgentEndToEndTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path ROOT=Path.of("target","coding-e2e-"+UUID.randomUUID()).toAbsolutePath();
    static final AtomicInteger CALLS=new AtomicInteger();
    static final HttpServer PROVIDER;
    static {
        try {
            PROVIDER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            PROVIDER.createContext("/v1/chat/completions",exchange->{
                try {
                    JsonNode request=JSON.readTree(exchange.getRequestBody());CALLS.incrementAndGet();
                    var messages=request.path("messages");int lastUser=-1;String prompt="";
                    for(int i=0;i<messages.size();i++)if("user".equals(messages.get(i).path("role").asText())){lastUser=i;prompt=messages.get(i).path("content").toString();}
                    boolean toolDone=false;for(int i=lastUser+1;i<messages.size();i++)if("tool".equals(messages.get(i).path("role").asText()))toolDone=true;
                    boolean inspect=prompt.contains("inspect");Map<String,Object> delta;
                    if(toolDone)delta=Map.of("role","assistant","content",inspect?"已读取 hello.txt，内容为 hello coding。":"已创建 hello.txt。");
                    else delta=Map.of("role","assistant","tool_calls",List.of(Map.of("index",0,"id","call_"+CALLS.get(),"type","function","function",Map.of("name",inspect?"read":"write","arguments",JSON.writeValueAsString(inspect?Map.of("path","hello.txt"):Map.of("path","hello.txt","content","hello coding\n"))))));
                    String chunk=JSON.writeValueAsString(Map.of("id","completion-fixture","object","chat.completion.chunk","created",1,"model","coding-fixture","choices",List.of(Map.of("index",0,"delta",delta,"finish_reason",toolDone?"stop":"tool_calls"))));
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
    @Test void actualSdkApprovalFilesContinuationDiffAndOwnerIsolation()throws Exception{
        var capability=ok(HttpMethod.GET,"/capabilities",null);assertThat(capability.path("available").asBoolean()).as(capability.toPrettyString()).isTrue();assertThat(capability.path("shellEnabled").asBoolean()).isFalse();
        String project=ok(HttpMethod.POST,"/projects",Map.of("name","真实链路离线验收")).path("id").asText();
        String session=ok(HttpMethod.POST,"/sessions",Map.of("projectId",project,"title","离线 pi 工具验收","approvalMode","ask")).path("id").asText();
        Map<String,Object> turn=Map.of("requestId",UUID.randomUUID().toString(),"prompt","create hello.txt","revision",0,"approvalMode","ask");
        ok(HttpMethod.POST,"/sessions/"+session+"/turns",turn);
        JsonNode waiting=waitFor(session,s->s.path("status").asText().equals("WAITING_APPROVAL"));
        assertThat(ok(HttpMethod.GET,"/projects/"+project+"/files",null)).isEmpty();
        String approval="";for(var e:waiting.path("events"))if(e.path("type").asText().equals("approval_required"))approval=e.path("approvalId").asText();assertThat(approval).isNotBlank();
        assertThat(call(HttpMethod.POST,"/sessions/"+session+"/approvals/"+approval,Map.of("approved",true),"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").getStatusCode().is4xxClientError()).isTrue();
        ok(HttpMethod.POST,"/sessions/"+session+"/approvals/"+approval,Map.of("approved",true));
        JsonNode done=waitFor(session,s->s.path("status").asText().equals("SUCCEEDED"));
        assertThat(ok(HttpMethod.GET,"/projects/"+project+"/file?path=hello.txt",null).path("content").asText()).isEqualTo("hello coding\n");
        JsonNode changes=ok(HttpMethod.GET,"/sessions/"+session+"/diff",null);assertThat(changes.size()).isEqualTo(1);assertThat(changes.get(0).path("path").asText()).isEqualTo("hello.txt");
        int calls=CALLS.get();ok(HttpMethod.POST,"/sessions/"+session+"/turns",turn);assertThat(CALLS.get()).isEqualTo(calls);
        ok(HttpMethod.POST,"/sessions/"+session+"/turns",Map.of("requestId",UUID.randomUUID().toString(),"prompt","inspect hello.txt","revision",done.path("revision").asInt(),"approvalMode","on-risk"));
        JsonNode resumed=waitFor(session,s->s.path("status").asText().equals("SUCCEEDED"));assertThat(resumed.path("turns").size()).isEqualTo(2);assertThat(resumed.toString()).contains("hello coding");
        assertThat(call(HttpMethod.GET,"/sessions/"+session,null,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").getStatusCode().is4xxClientError()).isTrue();
        assertThat(call(HttpMethod.GET,"/projects/"+project+"/file?path=hello.txt",null,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").getStatusCode().is4xxClientError()).isTrue();
        assertThat(CALLS.get()).isEqualTo(4);
        Files.createDirectories(ROOT);JSON.writerWithDefaultPrettyPrinter().writeValue(ROOT.resolve("verification.json").toFile(),Map.of("actualSdk",true,"httpModelCalls",CALLS.get(),"paidModelCalls",0,"project",project,"session",session,"status","PASSED","checks",List.of("HTTP identity","real JSONL child","pi SDK","approval prevents early write","actual file write","diff","idempotent replay","second-process history/read","cross-owner denied")));
    }
}
