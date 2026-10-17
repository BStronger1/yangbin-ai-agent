package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yangbin.yangbinaiagent.models.UnconfiguredChatModel;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import static org.assertj.core.api.Assertions.assertThat;

/** Real keyless Spring HTTP -> encrypted profiles -> Java -> installed pi SDK -> loopback provider.
 * No controller/model/runtime mocks, no real provider requests, no global model-profile store. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.profiles.active=prod","app.model.provider=openai","app.model.api-key=",
    "app.model.name=unconfigured-system-model","spring.ai.dashscope.api-key=development-placeholder",
    "app.rag.enabled=false","app.rag.cloud-enabled=false","spring.ai.mcp.client.enabled=false",
    "app.internal-api-token=byok-http-fixture-internal","app.coding.enabled=true","app.coding.shell-enabled=false",
    "app.coding.api-key=","app.coding.model=","app.coding.timeout-seconds=90","app.models.master-key=",
    "app.tools.extended-enabled=false","app.tools.terminal-enabled=false"
})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ModelProfilesHttpEndToEndTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Path ROOT=Path.of("target","models-http-e2e-"+UUID.randomUUID()).toAbsolutePath();
    static final String USER_A="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",USER_B="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    static final String KEY_A="offline-http-profile-alpha",KEY_B="offline-http-profile-beta",KEY_USER="offline-http-private-owner";
    record Observed(String path,String model,String authorization,boolean streaming){}
    static final List<Observed> OBSERVED=new CopyOnWriteArrayList<>();
    static final HttpServer PROVIDER;
    static {
        try {
            PROVIDER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            PROVIDER.createContext("/",ModelProfilesHttpEndToEndTest::provider);
            PROVIDER.start();
        }catch(Exception error){throw new ExceptionInInitializerError(error);}
    }
    static String base(String profile){return "http://127.0.0.1:"+PROVIDER.getAddress().getPort()+"/gateway/"+profile;}
    static void provider(HttpExchange exchange)throws java.io.IOException {
        try {
            JsonNode request=JSON.readTree(exchange.getRequestBody());
            String model=request.path("model").asText(),route=exchange.getRequestURI().getPath();
            boolean streaming=request.path("stream").asBoolean();
            OBSERVED.add(new Observed(route,model,exchange.getRequestHeaders().getFirst("Authorization"),streaming));
            if(!route.equals("/gateway/alpha/chat/completions")&&!route.equals("/gateway/beta/chat/completions")){
                reply(exchange,"application/json",500,JSON.writeValueAsBytes(Map.of("error",Map.of("message","Unexpected test endpoint"))));return;
            }
            if(!streaming){
                reply(exchange,"application/json",200,JSON.writeValueAsBytes(Map.of("id","manus-selected-model","object","chat.completion","created",1,"model",model,
                    "choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content","Manus selected "+model),"finish_reason","stop")))));return;
            }
            JsonNode messages=request.path("messages");int lastUser=-1;String prompt="";
            for(int i=0;i<messages.size();i++)if("user".equals(messages.get(i).path("role").asText())){lastUser=i;prompt=messages.get(i).path("content").toString();}
            boolean toolDone=false;for(int i=lastUser+1;i<messages.size();i++)if("tool".equals(messages.get(i).path("role").asText()))toolDone=true;
            boolean inspect=prompt.contains("inspect");Map<String,Object> delta;
            if(toolDone)delta=Map.of("role","assistant","content",model+(inspect?" inspected witness.txt":" created witness.txt"));
            else delta=Map.of("role","assistant","tool_calls",List.of(Map.of("index",0,"id","call_"+OBSERVED.size(),"type","function","function",Map.of("name",inspect?"read":"write","arguments",JSON.writeValueAsString(inspect?Map.of("path","witness.txt"):Map.of("path","witness.txt","content","BYOK alpha witness\n"))))));
            String chunk=JSON.writeValueAsString(Map.of("id","byok-http-stream","object","chat.completion.chunk","created",1,"model",model,
                "choices",List.of(Map.of("index",0,"delta",delta,"finish_reason",toolDone?"stop":"tool_calls"))));
            reply(exchange,"text/event-stream",200,("data: "+chunk+"\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
        }finally{exchange.close();}
    }
    static void reply(HttpExchange e,String type,int status,byte[] body)throws java.io.IOException {e.getResponseHeaders().set("Content-Type",type);e.sendResponseHeaders(status,body.length);e.getResponseBody().write(body);}
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry){
        registry.add("app.models.dir",()->ROOT.resolve("profiles").toString());registry.add("app.coding.dir",()->ROOT.resolve("coding").toString());
        registry.add("app.model.base-url",()->base("must-not-call-global"));registry.add("app.coding.base-url",()->"");
        registry.add("app.coding.node",()->System.getProperty("coding.test.node","node"));
        for(String component:List.of("memory","plans","repairs","skills","tasks","work"))registry.add("app."+component+".dir",()->ROOT.resolve(component).toString());
        registry.add("app.rag.upload-dir",()->ROOT.resolve("knowledge").toString());registry.add("app.rag.index-dir",()->ROOT.resolve("rag").toString());
        registry.add("logging.file.name",()->ROOT.resolve("application.log").toString());
    }
    @AfterAll static void stopProvider(){PROVIDER.stop(0);}
    @Autowired TestRestTemplate http;
    @Autowired @Qualifier("appChatModel") ChatModel sharedModel;
    private final List<String> createdSessions=new ArrayList<>();
    @AfterEach void cancelUnfinishedTasks(){for(String id:createdSessions)call(HttpMethod.POST,"/ai/coding/sessions/"+id+"/cancel",null,"admin");}
    HttpHeaders headers(String owner){var headers=new HttpHeaders();headers.set("X-Internal-Token","byok-http-fixture-internal");headers.set("X-User-Id",owner);headers.set("X-User-Role",owner.equals("admin")?"admin":"user");headers.setContentType(MediaType.APPLICATION_JSON);return headers;}
    ResponseEntity<JsonNode> call(HttpMethod method,String url,Object body,String owner){var response=http.exchange(url,method,new HttpEntity<>(body,headers(owner)),JsonNode.class);assertThat(Objects.toString(response.getBody(),"")).doesNotContain(KEY_A,KEY_B,KEY_USER);return response;}
    JsonNode ok(HttpMethod method,String url,Object body,String owner){var response=call(method,url,body,owner);assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();return response.getBody();}
    JsonNode ok(HttpMethod method,String url,Object body){return ok(method,url,body,"admin");}
    Map<String,Object> profile(String name,String route,String model,String secret){return Map.of("name",name,"provider","openai-compatible","baseUrl",base(route),"model",model,"apiKey",secret);}
    JsonNode waitFor(String id,Predicate<JsonNode> condition)throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();JsonNode state=null;
        do{state=ok(HttpMethod.GET,"/ai/coding/sessions/"+id,null);if(condition.test(state))return state;if(Set.of("FAILED","INTERRUPTED").contains(state.path("status").asText()))throw new AssertionError(state.toPrettyString());Thread.sleep(80);}while(System.nanoTime()<deadline);
        throw new AssertionError("Timed out waiting for actual Coding runtime: "+state);
    }
    @Test void keylessHttpProfilesDriveRealCodingAndManusWithoutCrossOwnerOrHistoricalMutation()throws Exception {
        assertThat(sharedModel).isInstanceOf(UnconfiguredChatModel.class);
        assertThat(ok(HttpMethod.GET,"/ai/models",null).path("profiles")).isEmpty();
        assertThat(ok(HttpMethod.GET,"/ai/coding/capabilities?modelProfileId=system",null).path("available").asBoolean()).isFalse();
        assertThat(OBSERVED).isEmpty();
        String alpha=ok(HttpMethod.POST,"/ai/models/profiles",profile("Alpha","alpha","fixture-model-alpha",KEY_A)).path("id").asText();
        String beta=ok(HttpMethod.POST,"/ai/models/profiles",profile("Beta","beta","fixture-model-beta",KEY_B)).path("id").asText();
        ok(HttpMethod.PUT,"/ai/models/profiles/"+alpha,profile("Alpha updated","alpha","fixture-model-alpha",""));
        JsonNode catalog=ok(HttpMethod.GET,"/ai/models",null);assertThat(catalog.path("profiles").size()).isEqualTo(2);assertThat(catalog.toString()).doesNotContain("apiKey","ciphertext");
        for(var metadata:catalog.path("profiles"))assertThat(metadata.path("hasKey").asBoolean()).isTrue();
        // A numeric public address is configuration-only: no request to it is ever made.
        String personal=ok(HttpMethod.POST,"/ai/models/profiles",Map.of("name","Owner A only","provider","openai-compatible","baseUrl","https://8.8.8.8/v1","model","private-owner-model","apiKey",KEY_USER),USER_A).path("id").asText();
        assertThat(ok(HttpMethod.GET,"/ai/models",null,USER_A).path("profiles").size()).isEqualTo(1);
        assertThat(ok(HttpMethod.GET,"/ai/models",null,USER_B).path("profiles")).isEmpty();
        assertThat(call(HttpMethod.DELETE,"/ai/models/profiles/"+personal,null,USER_B).getStatusCode().is4xxClientError()).isTrue();
        assertThat(call(HttpMethod.PUT,"/ai/models/defaults",Map.of("modelProfileId",alpha),USER_A).getStatusCode().is4xxClientError()).isTrue();
        assertThat(call(HttpMethod.GET,"/ai/coding/capabilities?modelProfileId="+alpha,null,USER_A).getStatusCode().is4xxClientError()).isTrue();
        assertThat(call(HttpMethod.PUT,"/ai/models/profiles/"+alpha,profile("hijack","alpha","wrong-model","wrong-key"),USER_A).getStatusCode().is4xxClientError()).isTrue();
        ok(HttpMethod.PUT,"/ai/models/defaults",Map.of("modelProfileId",alpha));
        assertThat(ok(HttpMethod.GET,"/ai/coding/capabilities",null).path("available").asBoolean()).isTrue();
        String project=ok(HttpMethod.POST,"/ai/coding/projects",Map.of("name","BYOK actual HTTP project")).path("id").asText();
        String session=ok(HttpMethod.POST,"/ai/coding/sessions",Map.of("projectId",project,"title","Profile-owned pi task","approvalMode","ask")).path("id").asText();createdSessions.add(session);
        Map<String,Object> first=Map.of("requestId",UUID.randomUUID().toString(),"prompt","create witness.txt","revision",0,"approvalMode","ask","modelProfileId",alpha);
        ok(HttpMethod.POST,"/ai/coding/sessions/"+session+"/turns",first);
        JsonNode waiting=waitFor(session,state->"WAITING_APPROVAL".equals(state.path("status").asText()));
        assertThat(ok(HttpMethod.GET,"/ai/coding/projects/"+project+"/files",null)).isEmpty();
        String approval="";for(var event:waiting.path("events"))if("approval_required".equals(event.path("type").asText()))approval=event.path("approvalId").asText();assertThat(approval).isNotBlank();
        assertThat(call(HttpMethod.POST,"/ai/coding/sessions/"+session+"/approvals/"+approval,Map.of("approved",true),USER_A).getStatusCode().is4xxClientError()).isTrue();
        ok(HttpMethod.POST,"/ai/coding/sessions/"+session+"/approvals/"+approval,Map.of("approved",true));
        JsonNode firstDone=waitFor(session,state->"SUCCEEDED".equals(state.path("status").asText()));
        assertThat(Files.readString(ROOT.resolve("coding/admin/projects").resolve(project).resolve("workspace/witness.txt"))).isEqualTo("BYOK alpha witness\n");
        JsonNode frozenAlpha=firstDone.path("turns").get(0).path("modelSnapshot").deepCopy();
        assertThat(frozenAlpha.path("profileId").asText()).isEqualTo(alpha);assertThat(frozenAlpha.path("model").asText()).isEqualTo("fixture-model-alpha");
        int calls=OBSERVED.size();ok(HttpMethod.POST,"/ai/coding/sessions/"+session+"/turns",first);assertThat(OBSERVED).hasSize(calls);
        var changedIdentity=new LinkedHashMap<>(first);changedIdentity.put("modelProfileId",beta);
        assertThat(call(HttpMethod.POST,"/ai/coding/sessions/"+session+"/turns",changedIdentity,"admin").getStatusCode().value()).isEqualTo(409);assertThat(OBSERVED).hasSize(calls);
        ok(HttpMethod.PUT,"/ai/models/defaults",Map.of("modelProfileId",beta));
        Map<String,Object> second=Map.of("requestId",UUID.randomUUID().toString(),"prompt","inspect witness.txt","revision",firstDone.path("revision").asInt(),"approvalMode","on-risk","modelProfileId",beta);
        ok(HttpMethod.POST,"/ai/coding/sessions/"+session+"/turns",second);
        JsonNode secondDone=waitFor(session,state->"SUCCEEDED".equals(state.path("status").asText()));assertThat(secondDone.path("turns").size()).isEqualTo(2);
        assertThat(secondDone.path("turns").get(0).path("modelSnapshot")).isEqualTo(frozenAlpha);assertThat(secondDone.path("turns").get(1).path("modelSnapshot").path("profileId").asText()).isEqualTo(beta);
        assertThat(call(HttpMethod.GET,"/ai/coding/sessions/"+session,null,USER_A).getStatusCode().is4xxClientError()).isTrue();
        var sseHeaders=headers("admin");sseHeaders.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
        var manus=http.exchange("/ai/manus/chat?message=reply-with-selected-model&modelProfileId="+alpha,HttpMethod.GET,new HttpEntity<>(null,sseHeaders),String.class);
        assertThat(manus.getStatusCode().is2xxSuccessful()).isTrue();assertThat(manus.getBody()).contains("Manus selected fixture-model-alpha","[DONE]").doesNotContain(KEY_A,KEY_B,"执行错误");
        assertThat(OBSERVED).hasSize(5);assertThat(OBSERVED.stream().filter(Observed::streaming)).hasSize(4);
        for(var request:OBSERVED){boolean isAlpha=request.path().contains("/alpha/");assertThat(request.model()).isEqualTo(isAlpha?"fixture-model-alpha":"fixture-model-beta");assertThat(request.authorization()).isEqualTo("Bearer "+(isAlpha?KEY_A:KEY_B));}
        assertThat(OBSERVED.getLast().streaming()).isFalse();assertThat(OBSERVED.getLast().model()).isEqualTo("fixture-model-alpha");
        ok(HttpMethod.PUT,"/ai/models/profiles/"+alpha,profile("Alpha future version","alpha","future-model-alpha",""));
        ok(HttpMethod.DELETE,"/ai/models/profiles/"+beta,null);
        assertThat(ok(HttpMethod.GET,"/ai/models",null).path("defaultModelId").asText()).isEqualTo("system");
        JsonNode historical=ok(HttpMethod.GET,"/ai/coding/sessions/"+session,null);assertThat(historical.path("turns").get(0).path("modelSnapshot")).isEqualTo(frozenAlpha);assertThat(historical.path("turns").get(1).path("modelSnapshot").path("model").asText()).isEqualTo("fixture-model-beta");
        calls=OBSERVED.size();ok(HttpMethod.POST,"/ai/coding/sessions/"+session+"/turns",second);assertThat(OBSERVED).hasSize(calls);
        ok(HttpMethod.DELETE,"/ai/models/profiles/"+personal,null,USER_A);
        assertThat(ok(HttpMethod.GET,"/ai/models",null,USER_A).path("profiles")).isEmpty();
        try(var paths=Files.walk(ROOT)){for(Path file:paths.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".json")||p.toString().endsWith(".jsonl")).toList())assertThat(Files.readString(file)).as(file.toString()).doesNotContain(KEY_A,KEY_B,KEY_USER);}
        Files.createDirectories(ROOT);JSON.writerWithDefaultPrettyPrinter().writeValue(ROOT.resolve("verification.json").toFile(),Map.of("status","PASSED","keylessSpringContext",true,"realHttp",true,"actualPiSdk",true,"loopbackModelRequests",OBSERVED.size(),"paidModelRequests",0,"project",project,"session",session,"checks",List.of("encrypted owner-scoped CRUD","no key in HTTP metadata or stored task events","default and explicit profile selection","approval before actual write","cross-account denial","request-id model binding","history survives profile changes/deletion","same selected profile through Manus HTTP")));
    }
}
