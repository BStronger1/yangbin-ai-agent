package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.ai.tool.ToolCallback;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.profiles.active=prod","app.internal-api-token=tenant-test-token","app.model.provider=openai","app.model.api-key=test-key","app.model.name=test-model",
    "app.rag.enabled=true","app.rag.embedding-model=local-test","app.tools.terminal-enabled=true",
    "spring.ai.mcp.client.enabled=true","spring.ai.mcp.client.request-timeout=10s"
})
class EnabledFeaturesTest {
    com.yangbin.yangbinaiagent.security.UserContext.Scope identity;
    @org.junit.jupiter.api.BeforeEach void identity(){identity=com.yangbin.yangbinaiagent.security.UserContext.open(com.yangbin.yangbinaiagent.security.UserContext.ADMIN);
        http.getRestTemplate().getInterceptors().clear();
        http.getRestTemplate().getInterceptors().add((request,body,execution)->{
            request.getHeaders().set("X-Internal-Token","tenant-test-token");
            if(!request.getHeaders().containsKey("X-User-Id")){request.getHeaders().set("X-User-Id","admin");request.getHeaders().set("X-User-Role","admin");}
            return execution.execute(request,body);
        });
    }
    @org.junit.jupiter.api.AfterEach void clearIdentity(){identity.close();}
    static final ObjectMapper json=new ObjectMapper();
    static final AtomicReference<String> lastChat=new AtomicReference<>();
    static HttpServer provider;
    static Process mcp;
    static int mcpPort;
    static Path index;
    static {
        try {
            index=Files.createTempDirectory("yangbin-rag-test-");
            provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            provider.createContext("/v1/embeddings",exchange->{
                var input=json.readTree(exchange.getRequestBody()).get("input");
                int count=input.isArray()?input.size():1;
                List<Map<String,Object>> data=new ArrayList<>();
                for(int i=0;i<count;i++)data.add(Map.of("object","embedding","index",i,"embedding",(input.isArray()?input.get(i):input).asText().contains("UNIQUE-KB-ALPHA")?List.of(0.0,1.0,0.0):List.of(1.0,0.0,0.0)));
                byte[] bytes=json.writeValueAsBytes(Map.of("object","list","model","local-test","data",data,"usage",Map.of("prompt_tokens",0,"total_tokens",0)));
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });
            provider.createContext("/v1/chat/completions",exchange->{
                lastChat.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                var request=json.readTree(lastChat.get());
                if(!request.path("stream").asBoolean(false)) {
                    String content="{\"title\":\"轻松相处的一天\",\"summary\":\"活动与费用均为估算\",\"stops\":[{\"time\":\"14:00\",\"activity\":\"咖啡与交流\",\"estimatedCost\":50,\"note\":\"请确认店铺营业时间\"},{\"time\":\"16:00\",\"activity\":\"室内展览\",\"estimatedCost\":50,\"note\":\"请确认票价\"}],\"alternatives\":[\"雨天选择室内活动\"],\"cautions\":[\"价格和地点待确认\"]}";
                    byte[] bytes=json.writeValueAsBytes(Map.of("id","plan-test","object","chat.completion","created",1,"model","test-model","choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content",content),"finish_reason","stop"))));
                    exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();return;
                }
                String data="data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"feature-ok\"},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n";
                byte[] bytes=data.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });
            provider.start();
            try(var socket=new ServerSocket(0)){mcpPort=socket.getLocalPort();}
            Path jar=Path.of("yangbin-image-search-mcp-server/target/yangbin-image-search-mcp-server-0.0.1-SNAPSHOT.jar").toAbsolutePath();
            if(!Files.isRegularFile(jar))throw new IllegalStateException("Build the MCP module before running EnabledFeaturesTest");
            mcp=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString(),
                    "--server.port="+mcpPort,"--server.address=127.0.0.1","--spring.profiles.active=sse",
                    "--logging.file.name="+index.resolve("mcp.log"),"--app.knowledge-dir="+Path.of("src/main/resources/document").toAbsolutePath())
                    .redirectErrorStream(true).redirectOutput(index.resolve("mcp-console.log").toFile()).start();
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            boolean ready=false;
            for(int i=0;i<120;i++){
                if(!mcp.isAlive())throw new IllegalStateException("MCP server exited: "+Files.readString(index.resolve("mcp-console.log")));
                try{
                    ready=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+mcpPort+"/ready")).timeout(Duration.ofSeconds(1)).build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;
                }catch(Exception ignored){}
                if(ready)break;Thread.sleep(250);
            }
            if(!ready)throw new IllegalStateException("MCP server did not become ready");
        }catch(Exception e){if(mcp!=null)mcp.destroyForcibly();if(provider!=null)provider.stop(0);throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry properties){
        properties.add("app.model.base-url",()->"http://127.0.0.1:"+provider.getAddress().getPort());
        properties.add("app.rag.embedding-base-url",()->"http://127.0.0.1:"+provider.getAddress().getPort());
        properties.add("app.rag.index-dir",()->index.toString());
        properties.add("app.rag.upload-dir",()->index.resolve("uploads").toString());
        properties.add("app.plans.dir",()->index.resolve("plans").toString());
        properties.add("app.tasks.dir",()->index.resolve("tasks").toString());
        properties.add("app.skills.dir",()->index.resolve("skills").toString());
        properties.add("spring.ai.mcp.client.sse.connections.knowledge.url",()->"http://127.0.0.1:"+mcpPort);
    }
    @AfterAll static void shutdown(){provider.stop(0);mcp.destroyForcibly();}
    @Autowired TestRestTemplate http;
    @Autowired @Qualifier("allTools") ToolCallback[] tools;
    ToolCallback tool(String suffix){return Arrays.stream(tools).filter(t->t.getToolDefinition().name().endsWith(suffix)).findFirst().orElseThrow();}
    @Test void ragRetrievesSourcesAndAddsEvidenceToModelPrompt(){
        String search=http.getForObject("/ai/rag/search?query=communication",String.class);
        assertThat(search).contains("source","content","score",".md");
        String stream=http.getForObject("/ai/love_app/chat/sse?message=communication&chatId=rag-check&mode=rag",String.class);
        assertThat(stream).contains("feature-ok","[DONE]");
        assertThat(lastChat.get()).contains("资料1",".md");
    }
    @Test void mcpDiscoversAndExecutesRealRemoteTools(){
        assertThat(tool("currentTime").call("{\"timezone\":\"Asia/Shanghai\"}")).contains("+08:00");
        assertThat(tool("listKnowledgeDocuments").call("{}")).contains(".md");
        String filename="恋爱常见问题和回答 - 已婚篇.md";
        assertThat(tool("readKnowledgeDocument").call("{\"filename\":\""+filename+"\"}")).contains("已婚");
        String stream=http.getForObject("/ai/love_app/chat/sse?message=hello&chatId=mcp-check&mode=mcp",String.class);
        assertThat(stream).contains("feature-ok","[DONE]");
        assertThat(lastChat.get()).contains("currentTime","listKnowledgeDocuments");
    }
    @Test void terminalRegisteredAndExecutable(){
        assertThat(tool("executeTerminalCommand").call("{\"command\":\"echo terminal-feature-ok\"}")).contains("terminal-feature-ok","Exit code: 0");
        assertThat(http.getForObject("/ai/capabilities",String.class)).contains("\"rag\":true","\"terminal\":true","currentTime");
    }

    @Test void uploadIsSearchableAndDeleteRemovesEvidence()throws Exception{
        var body=new org.springframework.util.LinkedMultiValueMap<String,Object>();
        body.add("file",new org.springframework.core.io.ByteArrayResource("UNIQUE-KB-ALPHA rain date advice".getBytes(StandardCharsets.UTF_8)) {public String getFilename(){return "uploaded-advice.md";}});
        var headers=new org.springframework.http.HttpHeaders();headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        var response=http.postForEntity("/ai/knowledge",new org.springframework.http.HttpEntity<>(body,headers),String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String id=json.readTree(response.getBody()).get("id").asText();
        assertThat(http.getForObject("/ai/rag/search?query=UNIQUE-KB-ALPHA",String.class)).contains("uploaded-advice.md");
        assertThat(http.getForObject("/ai/knowledge/"+id,String.class)).contains("READY","UNIQUE-KB-ALPHA");
        http.delete("/ai/knowledge/"+id);
        assertThat(http.getForObject("/ai/rag/search?query=UNIQUE-KB-ALPHA",String.class)).doesNotContain("uploaded-advice.md");
    }
    @Test void mcpManagementRunsToolsAndRejectsArbitraryTools()throws Exception{
        String name=tool("currentTime").getToolDefinition().name();
        var result=http.postForEntity("/ai/mcp/call",Map.of("name",name,"arguments",Map.of("timezone","Asia/Shanghai")),String.class);
        assertThat(result.getBody()).contains("\"success\":true","+08:00","durationMs");
        assertThat(http.getForObject("/ai/mcp/history",String.class)).contains(name);
        assertThat(http.postForEntity("/ai/mcp/call",Map.of("name","executeTerminalCommand","arguments",Map.of("command","echo no")),String.class).getStatusCode().value()).isEqualTo(400);
        assertThat(http.postForEntity("/ai/mcp/call",Map.of("name",name,"arguments",Map.of("timezone",42)),String.class).getStatusCode().value()).isEqualTo(400);
    }
    @Test void streamingEventsContainActualRetrievalResults(){
        var stream=http.getForObject("/ai/love_app/chat/events?message=communication&chatId=trace-check&mode=rag",String.class);
        assertThat(stream).contains("event:trace","retrieval","sources","durationMs","feature-ok","[DONE]");
    }
    @Test void plansPreserveVersionsAndExportActualPdf()throws Exception{
        var request=new HashMap<String,Object>(Map.of("city","上海","budget",500,"date","2026-10-20","startTime","14:00","hours",6,"interests","咖啡","transport","公共交通","revision",""));
        var created=http.postForEntity("/ai/plans",request,String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        String id=json.readTree(created.getBody()).get("id").asText();
        request.put("budget",200);request.put("revision","预算降低");
        var revised=http.postForEntity("/ai/plans/"+id+"/revise",Map.of("expectedVersion",1,"requirements",request),String.class);
        assertThat(revised.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(revised.getBody()).get("version").asInt()).isEqualTo(2);
        var versions=json.readTree(http.getForObject("/ai/plans/"+id,String.class));
        assertThat(versions.size()).isEqualTo(2);assertThat(versions.get(1).get("requirements").get("budget").asInt()).isEqualTo(500);
        assertThat(http.postForEntity("/ai/plans/"+id+"/revise",Map.of("expectedVersion",1,"requirements",request),String.class).getStatusCode().value()).isEqualTo(409);
        String url=json.readTree(http.postForObject("/ai/plans/"+id+"/2/pdf",null,String.class)).get("url").asText();
        byte[] pdf=http.getForObject(url.replaceFirst("^/api",""),byte[].class);
        assertThat(new String(pdf,0,4,StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(http.getForObject("/ai/plans",String.class)).contains(id);
    }

    private org.springframework.http.HttpHeaders tenant(String id){var h=new org.springframework.http.HttpHeaders();h.set("X-User-Id",id);h.set("X-User-Role","user");return h;}
    private String tenantGet(String url,String id){return http.exchange(url,org.springframework.http.HttpMethod.GET,new org.springframework.http.HttpEntity<>(tenant(id)),String.class).getBody();}
    @Test void privateDocumentsAndAsyncRetrievalStayWithinOwner()throws Exception{
        String a="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",b="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        var body=new org.springframework.util.LinkedMultiValueMap<String,Object>();
        body.add("file",new org.springframework.core.io.ByteArrayResource("UNIQUE-KB-ALPHA private evidence".getBytes(StandardCharsets.UTF_8)){public String getFilename(){return "private-a.md";}});
        var h=tenant(a);h.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        var upload=http.postForEntity("/ai/knowledge",new org.springframework.http.HttpEntity<>(body,h),String.class);
        assertThat(upload.getStatusCode().value()).isEqualTo(200);String id=json.readTree(upload.getBody()).get("id").asText();
        assertThat(tenantGet("/ai/knowledge",a)).contains("private-a.md");assertThat(tenantGet("/ai/knowledge",b)).doesNotContain("private-a.md");
        assertThat(tenantGet("/ai/rag/search?query=UNIQUE-KB-ALPHA",a)).contains("private-a.md");
        assertThat(tenantGet("/ai/rag/search?query=UNIQUE-KB-ALPHA",b)).doesNotContain("private-a.md");
        assertThat(tenantGet("/ai/love_app/chat/events?message=UNIQUE-KB-ALPHA&chatId=shared&mode=rag",a)).contains("private-a.md","[DONE]");
        assertThat(tenantGet("/ai/love_app/chat/events?message=UNIQUE-KB-ALPHA&chatId=shared&mode=rag",b)).doesNotContain("private-a.md").contains("[DONE]");
        assertThat(http.exchange("/ai/knowledge/"+id,org.springframework.http.HttpMethod.DELETE,new org.springframework.http.HttpEntity<>(tenant(b)),String.class).getStatusCode().value()).isEqualTo(400);
        assertThat(tenantGet("/ai/knowledge/"+id,a)).contains("private evidence");
        http.exchange("/ai/knowledge/"+id,org.springframework.http.HttpMethod.DELETE,new org.springframework.http.HttpEntity<>(tenant(a)),String.class);
    }
    @Test void planVersionsPdfAndConversationMemoryArePrivate()throws Exception{
        String a="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",b="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        var requirements=Map.of("city","上海","budget",500,"date","2026-10-20","startTime","14:00","hours",6,"interests","咖啡","transport","公共交通","revision","");
        String result=http.postForObject("/ai/plans",new org.springframework.http.HttpEntity<>(requirements,tenant(a)),String.class);
        String id=json.readTree(result).get("id").asText();
        assertThat(tenantGet("/ai/plans",b)).doesNotContain(id);assertThat(tenantGet("/ai/plans/"+id,b)).isEqualTo("[]");
        assertThat(http.postForEntity("/ai/plans/"+id+"/1/pdf",new org.springframework.http.HttpEntity<>(tenant(b)),String.class).getStatusCode().value()).isEqualTo(400);
        String url=json.readTree(http.postForObject("/ai/plans/"+id+"/1/pdf",new org.springframework.http.HttpEntity<>(tenant(a)),String.class)).get("url").asText().replaceFirst("^/api","");
        assertThat(http.exchange(url,org.springframework.http.HttpMethod.GET,new org.springframework.http.HttpEntity<>(tenant(b)),byte[].class).getStatusCode().value()).isEqualTo(404);
        assertThat(http.exchange(url,org.springframework.http.HttpMethod.GET,new org.springframework.http.HttpEntity<>(tenant(a)),byte[].class).getStatusCode().value()).isEqualTo(200);
        tenantGet("/ai/love_app/chat/sync?message=PRIVATE_A_MARKER&chatId=collision",a);
        tenantGet("/ai/love_app/chat/sync?message=hello&chatId=collision",b);assertThat(lastChat.get()).doesNotContain("PRIVATE_A_MARKER");
        tenantGet("/ai/love_app/chat/sync?message=continue&chatId=collision",a);assertThat(lastChat.get()).contains("PRIVATE_A_MARKER");
    }
    @Test void toolCapabilitiesAndMcpHistoryRespectAccount()throws Exception{
        String a="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",b="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String caps=tenantGet("/ai/capabilities",a);assertThat(caps).contains("\"terminal\":false").doesNotContain("executeTerminalCommand");
        String name=tool("currentTime").getToolDefinition().name();
        var result=http.postForObject("/ai/mcp/call",new org.springframework.http.HttpEntity<>(Map.of("name",name,"arguments",Map.of("timezone","Asia/Shanghai")),tenant(a)),String.class);
        String id=json.readTree(result).get("id").asText();assertThat(tenantGet("/ai/mcp/history",a)).contains(id);assertThat(tenantGet("/ai/mcp/history",b)).doesNotContain(id);
    }
    @Test void savedConversationEndpointsEnforceOwnerAndDeletion()throws Exception{
        String a="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",b="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",id="history-"+java.util.UUID.randomUUID();
        tenantGet("/ai/love_app/chat/events?message=PRIVATE_HISTORY_MARKER&chatId="+id+"&mode=chat",a);
        assertThat(tenantGet("/ai/conversations/"+id+"?mode=chat",a)).contains("PRIVATE_HISTORY_MARKER","feature-ok");
        assertThat(tenantGet("/ai/conversations/"+id+"?mode=chat",b)).isEqualTo("[]");
        http.exchange("/ai/conversations/"+id+"?mode=chat",org.springframework.http.HttpMethod.DELETE,new org.springframework.http.HttpEntity<>(tenant(b)),String.class);
        assertThat(tenantGet("/ai/conversations",a)).contains(id);assertThat(tenantGet("/ai/conversations",b)).doesNotContain(id);
        http.exchange("/ai/conversations/"+id+"?mode=chat",org.springframework.http.HttpMethod.DELETE,new org.springframework.http.HttpEntity<>(tenant(a)),String.class);
        assertThat(tenantGet("/ai/conversations/"+id+"?mode=chat",a)).isEqualTo("[]");
    }

    @Test void taskApiIsIdempotentAndProtectsReadRetryCancelAndPdfAcrossAccounts() throws Exception {
        String a="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",b="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        var requirements=Map.of("city","上海","budget",500,"date","2026-10-20","startTime","14:00","hours",6,"interests","咖啡","transport","公共交通","revision","");
        var body=Map.of("requestId",UUID.randomUUID().toString(),"prompt","生成两人出行任务和 PDF","requirements",requirements);
        var created=http.postForEntity("/ai/tasks",new org.springframework.http.HttpEntity<>(body,tenant(a)),String.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
        String id=json.readTree(created.getBody()).path("id").asText(); assertThat(id).isNotBlank();
        var repeated=http.postForObject("/ai/tasks",new org.springframework.http.HttpEntity<>(body,tenant(a)),String.class);
        assertThat(json.readTree(repeated).path("id").asText()).isEqualTo(id);
        assertThat(tenantGet("/ai/tasks",b)).doesNotContain(id);
        for(var method:List.of(org.springframework.http.HttpMethod.GET,org.springframework.http.HttpMethod.POST)) {
            for(String suffix:method==org.springframework.http.HttpMethod.GET?List.of(""):List.of("/retry","/cancel")) {
                var denied=http.exchange("/ai/tasks/"+id+suffix,method,new org.springframework.http.HttpEntity<>(tenant(b)),String.class);
                assertThat(denied.getStatusCode().is4xxClientError()).isTrue();
                assertThat(denied.getBody()).doesNotContain("生成两人出行任务", "private-a.md");
            }
        }
        var completed=new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50)).untilAsserted(()->{
            var task=json.readTree(tenantGet("/ai/tasks/"+id,a));
            assertThat(task.path("status").asText()).as(task.toString()).isEqualTo("SUCCEEDED"); completed.set(task);
        });
        String url=completed.get().path("pdfUrl").asText().replaceFirst("^/api","");
        assertThat(http.exchange(url,org.springframework.http.HttpMethod.GET,new org.springframework.http.HttpEntity<>(tenant(a)),byte[].class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.exchange(url,org.springframework.http.HttpMethod.GET,new org.springframework.http.HttpEntity<>(tenant(b)),byte[].class).getStatusCode().value()).isEqualTo(404);
        assertThat(completed.get().path("metrics").path("modelCalls").asInt()).isEqualTo(1);
    }

    @Test void skillHttpCrudEnforcesTenantVersionsAndDisabledState() throws Exception {
        String a="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",b="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        var input=new HashMap<String,Object>(Map.of("name","PRIVATE_HTTP_SKILL","description","只属于账号 A","instructions","优先室内活动","useKnowledge",false,"exportPdf",false,"enabled",true));
        var created=http.postForEntity("/ai/skills",new org.springframework.http.HttpEntity<>(input,tenant(a)),String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        String id=json.readTree(created.getBody()).path("id").asText();
        assertThat(tenantGet("/ai/skills",a)).contains(id,"PRIVATE_HTTP_SKILL");
        assertThat(tenantGet("/ai/skills",b)).doesNotContain(id,"PRIVATE_HTTP_SKILL");
        assertThat(http.exchange("/ai/skills/"+id,org.springframework.http.HttpMethod.GET,new org.springframework.http.HttpEntity<>(tenant(b)),String.class).getStatusCode().is4xxClientError()).isTrue();
        input.put("version",1);input.put("enabled",false);
        assertThat(http.exchange("/ai/skills/"+id,org.springframework.http.HttpMethod.PUT,new org.springframework.http.HttpEntity<>(input,tenant(b)),String.class).getStatusCode().is4xxClientError()).isTrue();
        var updated=http.exchange("/ai/skills/"+id,org.springframework.http.HttpMethod.PUT,new org.springframework.http.HttpEntity<>(input,tenant(a)),String.class);
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(updated.getBody()).path("version").asInt()).isEqualTo(2);
        assertThat(json.readTree(updated.getBody()).path("enabled").asBoolean()).isFalse();
        assertThat(http.exchange("/ai/skills/"+id,org.springframework.http.HttpMethod.PUT,new org.springframework.http.HttpEntity<>(input,tenant(a)),String.class).getStatusCode().value()).isEqualTo(409);
        assertThat(http.exchange("/ai/skills/"+id+"?version=2",org.springframework.http.HttpMethod.DELETE,new org.springframework.http.HttpEntity<>(tenant(b)),String.class).getStatusCode().is4xxClientError()).isTrue();
        assertThat(http.exchange("/ai/skills/"+id+"?version=1",org.springframework.http.HttpMethod.DELETE,new org.springframework.http.HttpEntity<>(tenant(a)),String.class).getStatusCode().value()).isEqualTo(409);
        assertThat(http.exchange("/ai/skills/"+id+"?version=2",org.springframework.http.HttpMethod.DELETE,new org.springframework.http.HttpEntity<>(tenant(a)),String.class).getStatusCode().value()).isEqualTo(204);
        assertThat(tenantGet("/ai/skills",a)).doesNotContain(id,"PRIVATE_HTTP_SKILL");
    }

}
