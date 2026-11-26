package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Real authenticated HTTP, Java, Node/pi, loopback SSE provider and actual disk. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.profiles.active=prod","app.model.provider=openai","app.model.api-key=fixture-only-key",
        "app.model.name=work-fixture","app.rag.enabled=false","app.rag.cloud-enabled=false",
        "app.internal-api-token=work-fixture-internal","app.coding.enabled=true","app.coding.shell-enabled=false",
        // All model traffic is loopback; the scripted SSE stream deliberately omits usage.
        "app.governance.daily-tokens=2000000","app.coding.api-key=","app.coding.model=","app.coding.timeout-seconds=120"})
class WorkTransferHttpEndToEndTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Path ROOT=Path.of("target","work-transfer-e2e-"+UUID.randomUUID()).toAbsolutePath();
    private static final String EVIDENCE="/ai/work/evidence", CODING="/ai/coding";
    private static final String ORIGINAL="def orders(rows):\n    return rows[:-1]\n", PATCHED="def orders(rows):\n    return rows\n";
    private static final List<JsonNode> RECEIVED=new CopyOnWriteArrayList<>();
    private static final List<JsonNode> OTHER_RECEIVER=new CopyOnWriteArrayList<>();
    private static final CountDownLatch IN_FLIGHT=new CountDownLatch(1), RELEASE=new CountDownLatch(1);
    private static final HttpServer SERVER;
    static {
        try {
            SERVER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            SERVER.createContext("/v1/chat/completions",WorkTransferHttpEndToEndTest::respond);
            SERVER.createContext("/recipient-b/",WorkTransferHttpEndToEndTest::respond);
            SERVER.start();
        } catch(IOException e) { throw new ExceptionInInitializerError(e); }
    }
    private static void respond(HttpExchange exchange) throws IOException {
        try {
            JsonNode request=JSON.readTree(exchange.getRequestBody());
            RECEIVED.add(request);
            if(exchange.getRequestURI().getPath().startsWith("/recipient-b/")) OTHER_RECEIVER.add(request);
            var messages=request.path("messages");
            int lastUser=-1;
            for(int i=0;i<messages.size();i++) if("user".equals(messages.get(i).path("role").asText())) lastUser=i;
            String prompt=lastUser<0?"":messages.get(lastUser).path("content").toString();
            int tools=0;
            for(int i=lastUser+1;i<messages.size();i++) if("tool".equals(messages.get(i).path("role").asText())) tools++;
            Map<String,Object> args=null;
            String tool=null;
            if(prompt.contains("REVOKE_WINDOW") && tools==0){
                IN_FLIGHT.countDown();
                try { if(!RELEASE.await(60,TimeUnit.SECONDS)) throw new IOException("Fixture revoke timed out"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                tool="read";args=Map.of("path","calc.py");
            } else if(prompt.contains("CANDIDATE_WRITE")){
                if(tools==0){tool="read";args=Map.of("path","calc.py");}
                else if(tools==1){tool="write";args=Map.of("path","calc.py","content",PATCHED);}
            }
            Map<String,Object> delta=tool==null?Map.of("role","assistant","content",
                    prompt.contains("CANDIDATE_WRITE")?"已保存候选，样本 OCT-104。":"本轮已答复。"):
                    Map.of("role","assistant","tool_calls",List.of(Map.of("index",0,"id","call_"+RECEIVED.size(),
                            "type","function","function",Map.of("name",tool,"arguments",JSON.writeValueAsString(args)))));
            String chunk=JSON.writeValueAsString(Map.of("id","fixture","object","chat.completion.chunk","created",1,
                    "model","work-fixture","choices",List.of(Map.of("index",0,"delta",delta,"finish_reason",tool==null?"stop":"tool_calls"))));
            byte[] body=("data: "+chunk+"\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");
            exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);
        } finally {exchange.close();}
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){
        r.add("app.model.base-url",()->"http://127.0.0.1:"+SERVER.getAddress().getPort()+"/v1");
        r.add("app.coding.base-url",()->"");
        r.add("app.work.dir",()->ROOT.resolve("work").toString());
        r.add("app.models.dir",()->ROOT.resolve("models").toString());
        r.add("app.coding.dir",()->ROOT.resolve("coding").toString());
        r.add("app.governance.dir",()->ROOT.resolve("governance").toString());
        r.add("app.coding.node",()->System.getProperty("coding.test.node","node"));
    }
    @AfterAll static void shutdown(){RELEASE.countDown();SERVER.stop(0);}
    @Autowired TestRestTemplate http;
    @LocalServerPort int port;
    private static String id(){return UUID.randomUUID().toString();}
    private ResponseEntity<JsonNode> call(HttpMethod method,String url,Object body){
        var h=new HttpHeaders();h.set("X-Internal-Token","work-fixture-internal");h.set("X-User-Id","admin");h.set("X-User-Role","admin");
        h.setContentType(body instanceof LinkedMultiValueMap?MediaType.MULTIPART_FORM_DATA:MediaType.APPLICATION_JSON);
        return http.exchange(url,method,new HttpEntity<>(body,h),JsonNode.class);
    }
    private JsonNode ok(HttpMethod method,String url,Object body){
        var result=call(method,url,body);assertThat(result.getStatusCode().is2xxSuccessful()).as("%s %s %s",method,url,result).isTrue();return result.getBody();
    }
    private JsonNode post(String url,Object body){return ok(HttpMethod.POST,url,body);}
    private JsonNode get(String url){return ok(HttpMethod.GET,url,null);}
    private String grant(JsonNode report,String project,String purpose){
        return grant(report,project,purpose,"system");
    }
    private String grant(JsonNode report,String project,String purpose,String profile){
        var dest=get(EVIDENCE+"/destination?modelProfileId="+profile);
        var input=new LinkedHashMap<String,Object>();
        input.put("requestId",id());input.put("reportId",report.path("id").asText());input.put("sourceVersion",report.path("sourceVersion").asText());
        input.put("rows",List.of(5));input.put("projectId",project);input.put("modelProfileId",profile);input.put("destinationVersion",dest.path("version").asText());
        input.put("purpose",purpose);input.put("includeTotals",false);input.put("confirmExternal",true);
        return post(EVIDENCE+"/transfers",input).path("id").asText();
    }
    private JsonNode waitTerminal(String session) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(100).toNanos();
        var approvals=new HashSet<String>();JsonNode state=null;
        while(System.nanoTime()<deadline){
            state=get(CODING+"/sessions/"+session);
            if(!Set.of("RUNNING","WAITING_APPROVAL","QUEUED").contains(state.path("status").asText())) return state;
            var turns=state.path("turns");
            String current=turns.get(turns.size()-1).path("id").asText();
            for(var event:state.path("events"))
                if(current.equals(event.path("turnId").asText()) && "approval_resolved".equals(event.path("type").asText()))
                    approvals.add(event.path("approvalId").asText());
            for(var event:state.path("events"))
                if(current.equals(event.path("turnId").asText()) && "approval_required".equals(event.path("type").asText())&&approvals.add(event.path("approvalId").asText()))
                    post(CODING+"/sessions/"+session+"/approvals/"+event.path("approvalId").asText(),Map.of("approved",true));
            Thread.sleep(60);
        }
        throw new AssertionError("Timed out waiting for scenario: "+state);
    }
    private Map<String,Object> turn(String request,String prompt,int revision,String transfer){
        var input=new LinkedHashMap<String,Object>(Map.of("requestId",request,"prompt",prompt,"revision",revision,"approvalMode","on-risk","executionMode","react","modelProfileId","system"));
        if(transfer!=null)input.put("transferId",transfer);
        return input;
    }
    private static String sha(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void editingTheSameModelProfileCannotSendSelectedRowsToANewReceiverWithoutConsent() throws Exception {
        String base="http://127.0.0.1:"+SERVER.getAddress().getPort();
        var profile=new LinkedHashMap<String,Object>(Map.of("name","Recipient binding","provider","openai-compatible",
                "baseUrl",base+"/v1","model","work-fixture","apiKey","receiver-fixture-only"));
        String profileId=post("/ai/models/profiles",profile).path("id").asText();
        var csv=new LinkedMultiValueMap<String,Object>();
        csv.add("file",new ByteArrayResource("key,amount,notes\nOMIT-1,1,a\nOMIT-2,2,b\nOMIT-3,3,c\nSEND-4,4,private-cell".getBytes(StandardCharsets.UTF_8)){
            @Override public String getFilename(){return "receiver.csv";}
        });
        String resource=post("/ai/work/partners/office/files",csv).path("id").asText();
        var report=post(EVIDENCE+"/reports",Map.of("requestId",id(),"partnerId","office","resourceId",resource,
                "idColumn","key","amountColumn","amount","reportedTotal","6"));
        String project=post(CODING+"/projects",Map.of("name","Destination scenario")).path("id").asText();
        String originalGrant=grant(report,project,"ANALYZE",profileId);
        profile.put("baseUrl",base+"/recipient-b/v1");profile.put("apiKey","");
        ok(HttpMethod.PUT,"/ai/models/profiles/"+profileId,profile);
        String session=post(CODING+"/sessions",Map.of("projectId",project,"approvalMode","on-risk")).path("id").asText();
        String turns=CODING+"/sessions/"+session+"/turns";
        var refused=turn(id(),"READ_SAMPLE",0,originalGrant);
        refused.put("modelProfileId",profileId);
        int before=OTHER_RECEIVER.size();
        assertThat(call(HttpMethod.POST,turns,refused).getStatusCode().is4xxClientError()).isTrue();
        assertThat(OTHER_RECEIVER.size()).isEqualTo(before);
        String confirmed=grant(report,project,"ANALYZE",profileId);
        var approved=turn(id(),"READ_SAMPLE",0,confirmed); approved.put("modelProfileId",profileId);
        post(turns,approved);
        var done=waitTerminal(session);
        assertThat(done.path("status").asText()).as(done.toString()).isEqualTo("SUCCEEDED");
        assertThat(OTHER_RECEIVER.size()).isEqualTo(before+1);
        assertThat(OTHER_RECEIVER.get(before).path("messages").toString()).contains("SEND-4").doesNotContain("OMIT-","private-cell");
    }
    @Test void selectedEvidenceCrossesOncePerAuthorizedCallAndPatchIsAppliedOnlyAfterReview() throws Exception {
        var csv=new LinkedMultiValueMap<String,Object>();
        csv.add("file",new ByteArrayResource(("order_id,amount,customer\nOCT-101,1100,customer-secret\nOCT-102,1200,hidden-two\n"
                +"OCT-103,1020,hidden-three\nOCT-104,680,private-selected-column").getBytes(StandardCharsets.UTF_8)){
            @Override public String getFilename(){return "orders.csv";}
        });
        String source=post("/ai/work/partners/office/files",csv).path("id").asText();
        var report=post(EVIDENCE+"/reports",Map.of("requestId",id(),"partnerId","office","resourceId",source,
                "idColumn","order_id","amountColumn","amount","reportedTotal","3320"));
        assertThat(report.path("actualTotal").asText()).isEqualTo("4000.00");
        var bad=post(EVIDENCE+"/reports/"+report.path("id").asText()+"/verify",Map.of("claimedTotal",4000,"claimedDifference",680,
                "claimedRow",4,"claimedOrderId","OCT-104","claimedAmount",680));
        assertThat(bad.path("verified").asBoolean()).isFalse();
        String project=post(CODING+"/projects",Map.of("name","Order fixture")).path("id").asText();
        String file=CODING+"/projects/"+project+"/file";
        ok(HttpMethod.PUT,file,Map.of("path","calc.py","content",ORIGINAL));
        String session=post(CODING+"/sessions",Map.of("projectId",project,"approvalMode","on-risk")).path("id").asText();
        String turns=CODING+"/sessions/"+session+"/turns", turn=id();
        String writeGrant=grant(report,project,"PROPOSE_PATCH");
        int start=RECEIVED.size();
        post(turns,turn(turn,"CANDIDATE_WRITE",0,writeGrant));
        var done=waitTerminal(session);
        assertThat(done.path("status").asText()).as(done.toString()).isEqualTo("SUCCEEDED");
        assertThat(RECEIVED.size()-start).isGreaterThanOrEqualTo(3);
        for(var req:RECEIVED.subList(start,RECEIVED.size())){
            assertThat(req.path("messages").toString()).contains("OCT-104")
                    .doesNotContain("OCT-101","OCT-102","OCT-103","customer-secret","private-selected-column","reportedTotal");
        }
        String turnUrl=turns+"/"+turn;
        var candidate=get(turnUrl+"/candidate");assertThat(candidate).hasSize(1);
        assertThat(candidate.get(0).path("after").asText()).isEqualTo(PATCHED);
        assertThat(get(file+"?path=calc.py").path("content").asText()).isEqualTo(ORIGINAL);
        int ordinary=RECEIVED.size();
        post(turns,turn(id(),"ORDINARY_TURN",done.path("revision").asInt(),null));
        var next=waitTerminal(session);
        assertThat(next.path("status").asText()).as(next.toString()).isEqualTo("SUCCEEDED");
        assertThat(RECEIVED.size()).isGreaterThan(ordinary);
        for(var req:RECEIVED.subList(ordinary,RECEIVED.size()))assertThat(req.path("messages").toString()).doesNotContain("OCT-104","CANDIDATE_WRITE","sourceVersion");

        var apply=Map.of("requestId",id(),"path","calc.py","oldHash",sha(ORIGINAL),"newHash",sha(PATCHED));
        ok(HttpMethod.PUT,file,Map.of("path","calc.py","content","external edit\n"));
        assertThat(call(HttpMethod.POST,turnUrl+"/applications",apply).getStatusCode().value()).isEqualTo(409);
        assertThat(get(file+"?path=calc.py").path("content").asText()).isEqualTo("external edit\n");
        ok(HttpMethod.PUT,file,Map.of("path","calc.py","content",ORIGINAL));
        var receipt=post(turnUrl+"/applications",apply);
        assertThat(receipt.path("phase").asText()).isEqualTo("CONFIRMED");
        assertThat(get(file+"?path=calc.py").path("content").asText()).isEqualTo(PATCHED);
        ok(HttpMethod.PUT,file,Map.of("path","calc.py","content","newer external edit\n"));
        assertThat(post(turnUrl+"/applications",apply)).isEqualTo(receipt);
        assertThat(get(file+"?path=calc.py").path("content").asText()).isEqualTo("newer external edit\n");
        assertThat(get(turnUrl+"/applications")).hasSize(1);

        String readGrant=grant(report,project,"ANALYZE");
        int beforeRevoke=RECEIVED.size();
        post(turns,turn(id(),"REVOKE_WINDOW",next.path("revision").asInt(),readGrant));
        try {
            assertThat(IN_FLIGHT.await(75,TimeUnit.SECONDS)).as("first authorized model request arrived").isTrue();
            assertThat(post(EVIDENCE+"/transfers/"+readGrant+"/revoke",Map.of()).path("revoked").asBoolean()).isTrue();
        } finally { RELEASE.countDown(); }
        var stopped=waitTerminal(session);
        assertThat(stopped.path("status").asText()).isNotIn("RUNNING","WAITING_APPROVAL","SUCCEEDED");
        assertThat(RECEIVED.size()-beforeRevoke).as("no subsequent model send after revocation").isEqualTo(1);
        var rejected=call(HttpMethod.POST,turns,turn(id(),"RETRY_REVOKED",stopped.path("revision").asInt(),readGrant));
        assertThat(rejected.getStatusCode().is4xxClientError()).isTrue();
        assertThat(RECEIVED.size()-beforeRevoke).isEqualTo(1);
        if(Boolean.getBoolean("v1.browser")){
            String browserProject=post(CODING+"/projects",Map.of("name","Browser order fixture")).path("id").asText();
            ok(HttpMethod.PUT,CODING+"/projects/"+browserProject+"/file",Map.of("path","calc.py","content",ORIGINAL));
            String browserSession=post(CODING+"/sessions",Map.of("projectId",browserProject,"approvalMode","on-risk")).path("id").asText();
            ProcessBuilder browser=new ProcessBuilder(System.getProperty("coding.test.node","node"),
                    System.getProperty("v1.browserScript","yangbin-ai-agent-frontend/tests/work-evidence-live.browser.mjs")).inheritIO();
            browser.environment().putAll(Map.of("V1_JAVA_PORT",Integer.toString(port),"V1_FIXTURE_TOKEN","work-fixture-internal",
                    "V1_SOURCE_ID",source,"V1_REPORT_ID",report.path("id").asText(),"V1_PROJECT_ID",browserProject,"V1_SESSION_ID",browserSession,
                    "V1_OWNER","admin","V1_SOURCE_NAME","orders.csv"));
            var process=browser.start();
            try {
                assertThat(process.waitFor(3,TimeUnit.MINUTES)).as("live browser scenario terminated").isTrue();
                assertThat(process.exitValue()).as("live browser assertions").isZero();
            } finally { if(process.isAlive())process.destroyForcibly(); }
        }
    }
}
