package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual HTTP -> authenticated scope -> durable memory -> fresh pi subprocess -> local provider.
 * Provider only captures the supplied context: these are engineering tests, not model quality. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.profiles.active=prod","app.model.provider=openai","app.model.api-key=",
 "app.model.name=unconfigured","spring.ai.dashscope.api-key=development-placeholder",
 "app.rag.enabled=false","app.rag.cloud-enabled=false","spring.ai.mcp.client.enabled=false",
 "app.internal-api-token=memory-http-fixture","app.coding.enabled=true","app.coding.shell-enabled=false",
 "app.coding.api-key=","app.coding.model=","app.coding.timeout-seconds=60","app.models.master-key=",
 "app.tools.extended-enabled=false","app.tools.terminal-enabled=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MemoryHttpEndToEndTest {
 static final ObjectMapper JSON=new ObjectMapper();
 static final Path ROOT=Path.of("target","memory-http-"+UUID.randomUUID()).toAbsolutePath();
 static final String OTHER="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
 static final List<String> REQUESTS=new CopyOnWriteArrayList<>();
 static final HttpServer PROVIDER;
 static {try {PROVIDER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);PROVIDER.createContext("/",exchange->{
  var body=JSON.readTree(exchange.getRequestBody());REQUESTS.add(body.toString());
  String chunk=JSON.writeValueAsString(Map.of("id","memory-fixture","object","chat.completion.chunk","created",1,"model","memory-fixture",
   "choices",List.of(Map.of("index",0,"delta",Map.of("role","assistant","content","fixture response"),"finish_reason","stop")),"usage",Map.of("prompt_tokens",100,"completion_tokens",3)));
  byte[] response=("data: "+chunk+"\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
  exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
 });PROVIDER.start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 @DynamicPropertySource static void configure(DynamicPropertyRegistry registry){
  registry.add("app.models.dir",()->ROOT.resolve("profiles").toString());registry.add("app.coding.dir",()->ROOT.resolve("coding").toString());
  registry.add("app.model.base-url",()->"http://127.0.0.1:"+PROVIDER.getAddress().getPort()+"/v1");registry.add("app.coding.base-url",()->"");
  registry.add("app.coding.node",()->System.getProperty("coding.test.node","node"));
  for(String component:List.of("governance","memory","plans","repairs","skills","tasks","work"))registry.add("app."+component+".dir",()->ROOT.resolve(component).toString());
  registry.add("app.rag.upload-dir",()->ROOT.resolve("knowledge").toString());registry.add("app.rag.index-dir",()->ROOT.resolve("rag").toString());registry.add("logging.file.name",()->ROOT.resolve("app.log").toString());
 }
 @Autowired TestRestTemplate http;
 @AfterAll static void close(){PROVIDER.stop(0);}
 ResponseEntity<JsonNode> call(HttpMethod method,String path,Object body,String owner){var h=new HttpHeaders();h.set("X-Internal-Token","memory-http-fixture");h.set("X-User-Id",owner);h.set("X-User-Role",owner.equals("admin")?"admin":"user");h.setContentType(MediaType.APPLICATION_JSON);return http.exchange(path,method,new HttpEntity<>(body,h),JsonNode.class);}
 JsonNode ok(HttpMethod method,String path,Object body){var r=call(method,path,body,"admin");assertThat(r.getStatusCode().is2xxSuccessful()).as(r.toString()).isTrue();return r.getBody();}
 String project(){return ok(HttpMethod.POST,"/ai/coding/projects",Map.of("name","M029 isolated fixture")).path("id").asText();}
 String prompt(String project,String profile,String text)throws Exception {
  String session=ok(HttpMethod.POST,"/ai/coding/sessions",Map.of("projectId",project,"title","Fresh memory session","approvalMode","ask")).path("id").asText();int before=REQUESTS.size();
  ok(HttpMethod.POST,"/ai/coding/sessions/"+session+"/turns",Map.of("requestId",UUID.randomUUID().toString(),"prompt",text,"revision",0,"approvalMode","ask","modelProfileId",profile));
  long deadline=System.nanoTime()+java.time.Duration.ofSeconds(45).toNanos();
  while(System.nanoTime()<deadline){JsonNode s=ok(HttpMethod.GET,"/ai/coding/sessions/"+session,null);String status=s.path("status").asText();
   if(status.equals("SUCCEEDED")){assertThat(REQUESTS.size()).isEqualTo(before+1);return REQUESTS.getLast();}
   if(Set.of("FAILED","CANCELLED","INTERRUPTED").contains(status))throw new AssertionError(s.toPrettyString());Thread.sleep(80);
  }ok(HttpMethod.POST,"/ai/coding/sessions/"+session+"/cancel",null);throw new AssertionError("Memory subprocess timeout");
 }
 @Test void freshRuntimeProcessesUseCurrentVersionAndForgetWithoutLeakingAcrossScopes()throws Exception {
  int initialRequests=REQUESTS.size();
  String profile=ok(HttpMethod.POST,"/ai/models/profiles",Map.of("name","memory local fixture","provider","openai-compatible","baseUrl","http://127.0.0.1:"+PROVIDER.getAddress().getPort()+"/v1","model","memory-fixture","apiKey","offline-fixture-credential")).path("id").asText();
  String p=project(),otherProject=project(),base="/ai/coding/projects/"+p+"/memory";
  assertThat(ok(HttpMethod.GET,base,null).path("items")).isEmpty();
  Map<String,Object> create=Map.of("requestId",UUID.randomUUID().toString(),"key","invoice-policy","content","Invoice currency is EUR_M029_WITNESS.","kind","CONSTRAINT");
  JsonNode first=ok(HttpMethod.POST,base,create);String id=first.path("id").asText();
  assertThat(ok(HttpMethod.POST,base,create).path("id").asText()).isEqualTo(id);
  assertThat(prompt(p,profile,"Use the invoice policy and describe the currency without tools.")).contains("EUR_M029_WITNESS");
  assertThat(call(HttpMethod.GET,base,null,OTHER).getStatusCode().is4xxClientError()).isTrue();
  assertThat(call(HttpMethod.PUT,base+"/"+id,Map.of("version",1,"key","invoice-policy","content","attack","kind","CONSTRAINT","status","ACTIVE"),OTHER).getStatusCode().is4xxClientError()).isTrue();
  assertThat(prompt(otherProject,profile,"Use the invoice policy and describe currency without tools.")).doesNotContain("EUR_M029_WITNESS");
  JsonNode updated=ok(HttpMethod.PUT,base+"/"+id,Map.of("version",1,"key","invoice-policy","content","Invoice currency is GBP_M029_LATEST.","kind","CONSTRAINT","status","ACTIVE"));
  assertThat(updated.path("version").asInt()).isEqualTo(2);
  assertThat(call(HttpMethod.PUT,base+"/"+id,Map.of("version",1,"key","invoice-policy","content","stale","kind","CONSTRAINT","status","ACTIVE"),"admin").getStatusCode().value()).isEqualTo(409);
  assertThat(prompt(p,profile,"Use the invoice policy and describe currency without tools.")).contains("GBP_M029_LATEST").doesNotContain("EUR_M029_WITNESS");
  ok(HttpMethod.DELETE,base+"/"+id+"?version=2",null);
  assertThat(ok(HttpMethod.GET,base,null).path("items")).isEmpty();
  assertThat(prompt(p,profile,"Use the invoice policy and describe currency without tools.")).doesNotContain("GBP_M029_LATEST","EUR_M029_WITNESS");
  assertThat(ok(HttpMethod.GET,base+"/search?q=invoice",null).path("items")).isEmpty();
  assertThat(call(HttpMethod.POST,base,Map.of("requestId",UUID.randomUUID().toString(),"key","api-key","content","sk-FAKEonly012345678901234567890","kind","FACT"),"admin").getStatusCode().value()).isEqualTo(400);
  assertThat(REQUESTS).hasSize(initialRequests+4);
  assertThat(Files.readString(ROOT.resolve("coding/admin/projects/"+p+"/memory.json"))).doesNotContain("EUR_M029_WITNESS","GBP_M029_LATEST");
  JSON.writerWithDefaultPrettyPrinter().writeValue(ROOT.resolve("verification.json").toFile(),Map.of("realHttp",true,"freshNodeProcesses",4,"loopbackRequests",4,"paidRequests",0,"modelQualityEvaluated",false));
 }
 @Test void naturalCandidateRequiresVersionedExplicitHttpDecision()throws Exception {
  String p=project(),base="/ai/coding/projects/"+p+"/memory";
  ok(HttpMethod.PUT,base+"/settings",Map.of("enabled",true,"autoExtract",true));
  String profile=ok(HttpMethod.POST,"/ai/models/profiles",Map.of("name","candidate local fixture","provider","openai-compatible","baseUrl","http://127.0.0.1:"+PROVIDER.getAddress().getPort()+"/v1","model","memory-fixture","apiKey","offline-fixture-credential")).path("id").asText();
  prompt(p,profile,"我们项目的开发语言是Java。");
  var list=ok(HttpMethod.GET,base+"/candidates",null);assertThat(list.path("candidates").size()).isGreaterThan(0);
  var c=list.path("candidates").get(0);String route=base+"/candidates/"+c.path("id").asText()+"/decision";
  var decision=Map.of("revision",list.path("revision").asInt(),"version",c.path("version").asInt(),"targetVersion",c.path("targetVersion").asInt(),"accept",true);
  assertThat(call(HttpMethod.POST,route,decision,OTHER).getStatusCode().is2xxSuccessful()).isFalse();
  assertThat(ok(HttpMethod.GET,base,null).path("items").size()).isZero();
  ok(HttpMethod.POST,route,decision);
  assertThat(ok(HttpMethod.GET,base,null).path("items").size()).isEqualTo(1);
  assertThat(call(HttpMethod.POST,route,decision,"admin").getStatusCode().value()).isEqualTo(409);
 }

}
