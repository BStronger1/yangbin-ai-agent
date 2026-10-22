package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Actual random-port Spring MVC + identity filter + JSON + Flux SSE + governed loopback model HTTP.
 * This deliberately does not stand in for the gateway's separate CSRF/browser-session tests. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.profiles.active=prod","app.model.provider=openai","app.model.api-key=offline-assistant-fixture-key",
 "app.model.name=assistant-http-fixture","spring.ai.dashscope.api-key=development-placeholder",
 "app.rag.enabled=false","app.rag.cloud-enabled=false","spring.ai.mcp.client.enabled=false",
 "app.internal-api-token=assistant-http-fixture-internal","app.coding.enabled=false","app.coding.shell-enabled=false",
 "app.tools.extended-enabled=false","app.tools.terminal-enabled=false","app.models.master-key=",
 "app.governance.daily-requests=2","app.governance.daily-tokens=100000","app.governance.output-tokens=128"
})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class AssistantHttpEndToEndTest {
 static final ObjectMapper JSON=new ObjectMapper();
 static final Path ROOT=Path.of("target","assistant-http-e2e-"+UUID.randomUUID()).toAbsolutePath();
 static final String A="a".repeat(32),B="b".repeat(32),BASE="/ai/assistant/partners/office";
 static final List<JsonNode> CALLS=new CopyOnWriteArrayList<>();
 static final CountDownLatch ENTERED=new CountDownLatch(1),RELEASE=new CountDownLatch(1);
 static final HttpServer PROVIDER;
 static {try {PROVIDER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);PROVIDER.createContext("/",AssistantHttpEndToEndTest::provider);PROVIDER.start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 static void provider(HttpExchange e)throws IOException {
  try {
   JsonNode input=JSON.readTree(e.getRequestBody());CALLS.add(input);
   if(!e.getRequestURI().getPath().equals("/v1/chat/completions"))throw new IOException("Unexpected fixture provider route");
   if(CALLS.size()==1){ENTERED.countDown();try{if(!RELEASE.await(15,TimeUnit.SECONDS))throw new IOException("Fixture release timed out");}catch(InterruptedException x){Thread.currentThread().interrupt();throw new IOException(x);}}
   byte[] bytes=JSON.writeValueAsBytes(Map.of("id","fixture-"+CALLS.size(),"object","chat.completion","created",1,"model","assistant-http-fixture",
    "choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content","HTTP合成成果：仅使用测试材料。"),"finish_reason","stop")),
    "usage",Map.of("prompt_tokens",10,"completion_tokens",5,"total_tokens",15)));
   e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);
  }finally{e.close();}
 }
 @DynamicPropertySource static void configure(DynamicPropertyRegistry r){
  r.add("app.model.base-url",()->"http://127.0.0.1:"+PROVIDER.getAddress().getPort()+"/v1");
  for(String component:List.of("governance","models","coding","memory","plans","repairs","skills","tasks","work"))r.add("app."+component+".dir",()->ROOT.resolve(component).toString());
  r.add("app.rag.upload-dir",()->ROOT.resolve("knowledge").toString());r.add("app.rag.index-dir",()->ROOT.resolve("rag").toString());r.add("logging.file.name",()->ROOT.resolve("application.log").toString());
 }
 @AfterAll static void shutdown(){RELEASE.countDown();PROVIDER.stop(0);}
 @Autowired TestRestTemplate http;
 HttpHeaders headers(String owner){var h=new HttpHeaders();h.set("X-Internal-Token","assistant-http-fixture-internal");h.set("X-User-Id",owner);h.set("X-User-Role","user");h.setContentType(MediaType.APPLICATION_JSON);h.setAccept(List.of(MediaType.ALL));return h;}
 ResponseEntity<JsonNode> call(HttpMethod method,String url,Object body,String owner){return http.exchange(url,method,new HttpEntity<>(body,headers(owner)),JsonNode.class);}
 JsonNode ok(HttpMethod method,String url,Object body){var r=call(method,url,body,A);assertThat(r.getStatusCode().is2xxSuccessful()).as(r.toString()).isTrue();return r.getBody();}
 Map<String,Object> input(String mode){var m=new LinkedHashMap<String,Object>();m.put("conversationId",UUID.randomUUID().toString());m.put("requestId",UUID.randomUUID().toString());m.put("expectedRevision",0);m.put("text","请整理给定的合成材料");m.put("skillId","auto");m.put("attachmentIds",List.of());m.put("mode",mode);m.put("confirmed",mode.equals("work"));if(mode.equals("work"))m.put("brief",Map.of("goal","整理合成材料","deliverable","简短可审阅文稿","constraints","不联网、不添加事实"));return m;}
 JsonNode settled(String id,String mode)throws Exception {long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();JsonNode p;do {p=ok(HttpMethod.GET,BASE+"/conversations/"+id+"/page?mode="+mode,null);if(!p.path("turns").get(0).path("status").asText().equals("RUNNING"))return p;Thread.sleep(40);}while(System.nanoTime()<deadline);throw new AssertionError("HTTP job failed to settle: "+p);}
 String terminalEvents(String id,String mode){var h=headers(A);h.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));var r=http.exchange(BASE+"/conversations/"+id+"/events?mode="+mode,HttpMethod.GET,new HttpEntity<>(null,h),String.class);assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(r.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_EVENT_STREAM)).isTrue();return r.getBody();}
 @Test void actualHttpSupportsDurableChatWorkSseAndRejectsWorkspaceOrAccountConfusion()throws Exception {
  // Parameter binding and request validation must reject before the first provider call.
  var chat=input("chat");var work=input("work");
  assertThat(call(HttpMethod.POST,BASE+"/jobs",chat,A).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  assertThat(call(HttpMethod.POST,BASE+"/jobs?mode=work",chat,A).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  var unconfirmed=new LinkedHashMap<>(work);unconfirmed.put("confirmed",false);
  assertThat(call(HttpMethod.POST,BASE+"/jobs?mode=work",unconfirmed,A).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  assertThat(call(HttpMethod.GET,BASE+"/conversations?mode=coding",null,A).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  var malformed=http.exchange(BASE+"/jobs?mode=chat",HttpMethod.POST,new HttpEntity<>("{broken-secret-fixture",headers(A)),JsonNode.class);assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);assertThat(malformed.getBody().path("message").asText()).isEqualTo("请求参数或消息格式无效");
  assertThat(CALLS).isEmpty();
  String cid=chat.get("conversationId").toString();
  var submitted=call(HttpMethod.POST,BASE+"/jobs?mode=chat",chat,A);assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);assertThat(submitted.getBody().path("mode").asText()).isEqualTo("chat");
  try {
   assertThat(ENTERED.await(5,TimeUnit.SECONDS)).isTrue();
   // Read the actual first SSE frame while the provider is blocked, then disconnect the socket.
   var req=HttpRequest.newBuilder(URI.create(http.getRootUri()+BASE+"/conversations/"+cid+"/events?mode=chat")).timeout(Duration.ofSeconds(8)).header("X-Internal-Token","assistant-http-fixture-internal").header("X-User-Id",A).header("X-User-Role","user").header("Accept","text/event-stream").GET().build();
   try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
    var stream=client.send(req,HttpResponse.BodyHandlers.ofInputStream());assertThat(stream.statusCode()).isEqualTo(200);
    try(var reader=new BufferedReader(new InputStreamReader(stream.body(),StandardCharsets.UTF_8))){var frame=new StringBuilder();for(int i=0;i<20;i++){String line=reader.readLine();if(line==null||line.isEmpty())break;frame.append(line).append('\n');}assertThat(frame.toString()).contains("event:state","RUNNING",cid);}
   }
   assertThat(ok(HttpMethod.GET,BASE+"/conversations/"+cid+"/page?mode=chat",null).path("turns").get(0).path("status").asText()).isEqualTo("RUNNING");
  }finally{RELEASE.countDown();}
  var completed=settled(cid,"chat");assertThat(completed.path("turns").get(0).path("status").asText()).isEqualTo("SUCCEEDED");assertThat(completed.path("turns").get(0).path("artifacts")).isEmpty();assertThat(terminalEvents(cid,"chat")).contains("event:state","event:done","SUCCEEDED","[DONE]");
  assertThat(call(HttpMethod.GET,BASE+"/conversations/"+cid+"/page?mode=work",null,A).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  assertThat(call(HttpMethod.GET,BASE+"/conversations/"+cid+"/page?mode=chat",null,B).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  assertThat(call(HttpMethod.GET,BASE+"/conversations/"+cid+"/events?mode=chat",null,B).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  assertThat(call(HttpMethod.POST,BASE+"/conversations/"+cid+"/stop?mode=work",null,A).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  assertThat(ok(HttpMethod.POST,BASE+"/jobs?mode=chat",chat).path("revision").asInt()).isEqualTo(1);assertThat(CALLS).hasSize(1);
  String wid=work.get("conversationId").toString();assertThat(call(HttpMethod.POST,BASE+"/jobs?mode=work",work,A).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);var worked=settled(wid,"work");var turn=worked.path("turns").get(0);assertThat(turn.path("status").asText()).isEqualTo("SUCCEEDED");assertThat(turn.path("brief").path("goal").asText()).isEqualTo("整理合成材料");assertThat(turn.path("artifacts").size()).isEqualTo(2);assertThat(terminalEvents(wid,"work")).contains("event:done","已确认工作目标与交付物");
  String artifact=turn.path("artifacts").get(0).path("id").asText(),resource="/ai/work/partners/office/resources/"+artifact+"/content";
  var downloaded=http.exchange(resource,HttpMethod.GET,new HttpEntity<>(null,headers(A)),byte[].class);assertThat(downloaded.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(new String(downloaded.getBody(),StandardCharsets.UTF_8)).contains("HTTP合成成果");assertThat(call(HttpMethod.GET,resource,null,B).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  assertThat(ok(HttpMethod.GET,BASE+"/conversations?mode=chat",null).size()).isEqualTo(1);assertThat(ok(HttpMethod.GET,BASE+"/conversations?mode=work",null).size()).isEqualTo(1);assertThat(ok(HttpMethod.GET,"/ai/work/partners/office/conversations",null)).isEmpty();assertThat(call(HttpMethod.GET,BASE+"/conversations?mode=work",null,B).getBody()).isEmpty();
  var hijack=new LinkedHashMap<>(chat);hijack.put("conversationId",wid);hijack.put("requestId",UUID.randomUUID().toString());hijack.put("expectedRevision",1);assertThat(call(HttpMethod.POST,BASE+"/jobs?mode=chat",hijack,A).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  // Both spaces already consumed the same two-request account allowance. A third accepted job cannot reach the provider.
  var denied=input("work");ok(HttpMethod.POST,BASE+"/jobs?mode=work",denied);assertThat(settled(denied.get("conversationId").toString(),"work").path("turns").get(0).path("status").asText()).isEqualTo("FAILED");assertThat(CALLS).hasSize(2);assertThat(ok(HttpMethod.GET,"/ai/governance",null).path("requests").asInt()).isEqualTo(2);
  for(var request:CALLS)assertThat(request.path("max_tokens").asInt()).isEqualTo(128);
  Files.createDirectories(ROOT);JSON.writerWithDefaultPrettyPrinter().writeValue(ROOT.resolve("verification.json").toFile(),Map.of("status","PASSED","realSpringHttp",true,"actualLoopbackProviderRequests",CALLS.size(),"paidModelRequests",0,"sseDisconnectKeepsTask",true,"sharedWorkspaceBudget",true,"crossAccountAndModeRejected",true,"csrfScope","gateway-tested-separately"));
 }
}
