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
class CodingLocalFilesEndToEndTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path ROOT=Path.of("target","coding-local-e2e-"+UUID.randomUUID()).toAbsolutePath();
    static final AtomicInteger CALLS=new AtomicInteger(),OUTPUT_CAP=new AtomicInteger();
    static final HttpServer PROVIDER;
    static {
        try {
            PROVIDER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            PROVIDER.createContext("/v1/chat/completions",exchange->{
                try {
                    JsonNode request=JSON.readTree(exchange.getRequestBody());CALLS.incrementAndGet();OUTPUT_CAP.accumulateAndGet(request.path("max_tokens").asInt(request.path("max_completion_tokens").asInt()),Math::max);
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
    static final String CLIENT=UUID.randomUUID().toString();
    @Autowired TestRestTemplate http;
    ResponseEntity<JsonNode> call(HttpMethod method,String url,Object body,String owner){
        HttpHeaders headers=new HttpHeaders();headers.set("X-Internal-Token","coding-fixture-internal");headers.set("X-User-Id",owner);headers.set("X-User-Role",owner.equals("admin")?"admin":"user");headers.setContentType(MediaType.APPLICATION_JSON);headers.set("X-Local-Client",CLIENT);
        return http.exchange("/ai/coding"+url,method,new HttpEntity<>(body,headers),JsonNode.class);
    }
    JsonNode ok(HttpMethod method,String url,Object body){var response=call(method,url,body,"admin");assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();return response.getBody();}
    JsonNode waitFor(String id,Predicate<JsonNode> predicate)throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();JsonNode state=null;
        do{state=ok(HttpMethod.GET,"/sessions/"+id,null);if(predicate.test(state))return state;if(Set.of("FAILED","INTERRUPTED").contains(state.path("status").asText()))throw new AssertionError(state.toPrettyString());Thread.sleep(80);}while(System.nanoTime()<deadline);
        throw new AssertionError("Timed out: "+state);
    }
    @Test void actualSdkLocalFileRelayAndNoServerCopy()throws Exception {
        Path device=ROOT.resolve("device-fixture");Files.createDirectories(device);
        String project=ok(HttpMethod.POST,"/projects",Map.of("name","本机目录链路","source","local")).path("id").asText();
        String session=ok(HttpMethod.POST,"/sessions",Map.of("projectId",project,"approvalMode","on-risk")).path("id").asText();
        int revision=0;Set<String> replies=new HashSet<>(),approvals=new HashSet<>();
        for(String prompt:List.of("create hello.txt","inspect hello.txt")) {
            ok(HttpMethod.POST,"/sessions/"+session+"/turns",Map.of("requestId",UUID.randomUUID().toString(),"prompt",prompt,"revision",revision,"approvalMode","on-risk","executionMode","react","localClientId",CLIENT));
            long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();boolean complete=false;
            while(System.nanoTime()<deadline) {
                var state=ok(HttpMethod.GET,"/sessions/"+session,null);String status=state.path("status").asText();
                if(status.equals("SUCCEEDED")){revision=state.path("revision").asInt();complete=true;break;}
                assertThat(status).as(state.toPrettyString()).isIn("RUNNING","WAITING_APPROVAL");
                for(var event:state.path("events"))if(event.path("type").asText().equals("approval_required")&&approvals.add(event.path("approvalId").asText())) {
                    assertThat(Files.exists(device.resolve("hello.txt"))).isFalse();
                    ok(HttpMethod.POST,"/sessions/"+session+"/approvals/"+event.path("approvalId").asText(),Map.of("approved",true));
                }
                var pending=call(HttpMethod.GET,"/sessions/"+session+"/local-files",null,"admin");
                if(pending.getStatusCode().value()==409){Thread.sleep(20);continue;}
                assertThat(pending.getStatusCode().is2xxSuccessful()).isTrue();
                for(var request:pending.getBody()) {
                    String rid=request.path("requestId").asText();assertThat(replies.add(rid)).isTrue();
                    var claim=ok(HttpMethod.POST,"/sessions/"+session+"/local-files/claim",Map.of("clientId",CLIENT,"requestId",rid));
                    String op=claim.path("operation").asText(),output="connected";
                    if(op.equals("write")){assertThat(approvals).hasSize(1);Files.writeString(device.resolve("hello.txt"),claim.path("arguments").path("content").asText());output="Wrote local hello.txt";}
                    if(op.equals("read"))output=Files.readString(device.resolve("hello.txt"));
                    ok(HttpMethod.POST,"/sessions/"+session+"/local-files/result",Map.of("clientId",CLIENT,"requestId",rid,"ok",true,"output",output));
                }
                Thread.sleep(30);
            }
            assertThat(complete).isTrue();
        }
        assertThat(Files.readString(device.resolve("hello.txt"))).isEqualTo("hello coding\n");
        assertThat(ROOT.resolve("admin/projects/"+project+"/workspace/hello.txt")).doesNotExist();
        assertThat(call(HttpMethod.GET,"/projects/"+project+"/files",null,"admin").getStatusCode().value()).isEqualTo(409);
        assertThat(CALLS.get()).isEqualTo(4);assertThat(replies).hasSize(4);
        assertThat(ok(HttpMethod.GET,"/sessions/"+session,null).toString()).contains("hello coding");
    }
}
