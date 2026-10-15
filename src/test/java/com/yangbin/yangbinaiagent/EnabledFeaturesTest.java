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
    "spring.profiles.active=prod","app.model.provider=openai","app.model.api-key=test-key","app.model.name=test-model",
    "app.rag.enabled=true","app.rag.embedding-model=local-test","app.tools.terminal-enabled=true",
    "spring.ai.mcp.client.enabled=true","spring.ai.mcp.client.request-timeout=10s"
})
class EnabledFeaturesTest {
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
}
