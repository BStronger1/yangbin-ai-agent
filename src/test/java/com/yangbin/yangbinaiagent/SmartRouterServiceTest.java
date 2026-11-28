package com.yangbin.yangbinaiagent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yangbin.yangbinaiagent.controller.CapabilitiesController;
import com.yangbin.yangbinaiagent.controller.RouterController;
import com.yangbin.yangbinaiagent.evaluation.ExecutionProvenance;
import com.yangbin.yangbinaiagent.governance.ModelGovernance;
import com.yangbin.yangbinaiagent.models.ModelProfileService;
import com.yangbin.yangbinaiagent.models.PrivateChatModels;
import com.yangbin.yangbinaiagent.rag.KnowledgeService;
import com.yangbin.yangbinaiagent.router.*;
import com.yangbin.yangbinaiagent.security.UserContext;
import com.yangbin.yangbinaiagent.work.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** All model and router traffic terminates on loopback; no credentials or external model calls. */
class SmartRouterServiceTest {
    static final String LOCAL="Qwen/Qwen3-4B", FLASH="deepseek-v4.1-flash-1017", PRO="deepseek-v4-pro-0813";
    static final String PRIVATE_PROMPT="PRIVATE-PROMPT-ROUTER008-DO-NOT-LOG";
    static final String PRIVATE_KEY="ROUTER008-TEST-KEY-DO-NOT-LOG";
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    final ExecutionProvenance provenance=new ExecutionProvenance("router008-test","fixture","default-model","");
    final AtomicInteger routerCalls=new AtomicInteger(), tierCalls=new AtomicInteger();
    final Queue<JsonNode> routerRequests=new ConcurrentLinkedQueue<>(), tierRequests=new ConcurrentLinkedQueue<>();
    final List<PartnerChatService> chats=new ArrayList<>();
    final List<SmartRouterClient> clients=new ArrayList<>();
    HttpServer server;
    ExecutorService serverThreads;
    volatile String replyModel=LOCAL, replyTier="local", brokenBody;
    volatile int replyStatus=200;
    volatile long delayMs;
    volatile boolean delayedBody;
    RouterProperties properties;
    SmartRouterService router;
    PrivateChatModels privateModels;
    ModelGovernance governance;
    ChatModel defaultModel;
    WorkCatalog catalog;
    WorkService work;
    PartnerResources resources;
    PartnerChatService chat;
    UserContext.Scope scope;

    @BeforeEach void setup() throws Exception {
        scope=UserContext.open(TaskWorkflowTest.A);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        serverThreads=Executors.newCachedThreadPool();server.setExecutor(serverThreads);
        server.createContext("/v1/route",this::routeResponse);
        server.createContext("/v1/chat/completions",this::tierResponse);
        server.start();
        properties=new RouterProperties();properties.setBaseUrl(url());properties.setTimeoutMs(2000);
        privateModels=new PrivateChatModels();
        governance=new ModelGovernance(json,root.resolve("governance"),Clock.systemUTC(),new ModelGovernance.Limits(2,8,100,1000000,100));
        ReflectionTestUtils.setField(privateModels,"governance",governance);
        router=new SmartRouterService(properties,newClient(),privateModels);
        defaultModel=mock(ChatModel.class);
        when(defaultModel.call(any(Prompt.class))).thenReturn(PartnerChatServiceTest.answer("default answer"));
        catalog=new WorkCatalog(json);work=new WorkService(json,defaultModel,catalog,provenance,root.resolve("work").toString());
        resources=new PartnerResources(json,catalog,root.resolve("work").toString());
        chat=createChat(null,privateModels);clearInvocations(defaultModel);
    }

    @AfterEach void close() {
        for(var c:chats)c.close();
        for(var client:clients)client.close();
        if(server!=null)server.stop(0);
        if(serverThreads!=null)serverThreads.shutdownNow();
        if(scope!=null)scope.close();
    }

    String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    SmartRouterClient newClient(){var client=new SmartRouterClient(properties,json);clients.add(client);return client;}
    PartnerChatService createChat(ModelProfileService profiles,PrivateChatModels factory) throws Exception {
        var result=new PartnerChatService(json,defaultModel,catalog,work,provenance,root.resolve("work").toString(),resources,profiles,factory);
        result.setSmartRouter(router);chats.add(result);return result;
    }
    PartnerChatService.Input request(String budget) {
        return new PartnerChatService.Input(UUID.randomUUID().toString(),UUID.randomUUID().toString(),0,PRIVATE_PROMPT,"auto",List.of(),"system","chat",null,false,null,budget);
    }
    PartnerChatService.Turn send(String budget) throws Exception {return chat.send("office",request(budget)).turns().getLast();}
    void enabled(){properties.setEnabled(true);}
    void configure(String tier){var t=properties.getTiers().get(tier);t.setModel("fixture-"+tier);t.setBaseUrl(url()+"/v1");t.setApiKey(PRIVATE_KEY);}
    ObjectNode validResponse(String budget) {
        var node=json.createObjectNode();node.put("intent","general_chat_qa");node.put("raw_model_id",replyModel);node.put("chosen_model",replyModel);
        node.put("tier",replyTier);node.put("promoted",false);node.put("route_confidence",0.95);node.put("joint_confidence",0.9);
        node.put("budget_mode",budget);node.put("estimated_saving_pct",replyModel.equals("abstain")?0.0:57.1);node.put("router_latency_ms",21.5);
        node.put("acceleration_used","2pass_lcp_exact");node.putNull("fallback_reason");
        var probs=node.putObject("candidate_probs");probs.put(LOCAL,0.25);probs.put(FLASH,0.25);probs.put(PRO,0.25);probs.put("abstain",0.25);return node;
    }
    void routeResponse(HttpExchange exchange) throws IOException {
        routerCalls.incrementAndGet();JsonNode request=json.readTree(exchange.getRequestBody());routerRequests.add(request);
        byte[] bytes=(brokenBody==null?json.writeValueAsString(validResponse(request.path("budget_mode").asText("balanced"))):brokenBody).getBytes(StandardCharsets.UTF_8);
        try {
            if(!delayedBody)delay();
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(replyStatus,bytes.length);
            if(delayedBody)delay();
            exchange.getResponseBody().write(bytes);
        } finally {exchange.close();}
    }
    void delay(){if(delayMs>0)try{Thread.sleep(delayMs);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}
    void tierResponse(HttpExchange exchange) throws IOException {
        tierCalls.incrementAndGet();var body=json.readTree(exchange.getRequestBody());tierRequests.add(body);
        String model=body.path("model").asText();
        byte[] response=json.writeValueAsBytes(Map.of("id","chatcmpl-fixture","object","chat.completion","created",1,"model",model,
            "choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content","tier answer "+model),"finish_reason","stop")),
            "usage",Map.of("prompt_tokens",10,"completion_tokens",5,"total_tokens",15)));
        try{exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);}finally{exchange.close();}
    }

    @Test void defaultFlagOffHasZeroNetworkCallsAndIdenticalDefaultBehavior() throws Exception {
        assertThat(properties.isEnabled()).isFalse();
        var outcome=router.route(PRIVATE_PROMPT,"chat","quality",defaultModel);
        assertThat(outcome.model()).isSameAs(defaultModel);assertThat(outcome.decision()).isNull();
        var turn=send("quality");assertThat(turn.status()).isEqualTo("SUCCEEDED");assertThat(turn.answer()).isEqualTo("default answer");
        assertThat(turn.routerDecision()).isNull();assertThat(routerCalls).hasValue(0);assertThat(tierCalls).hasValue(0);
        verify(defaultModel,times(1)).call(any(Prompt.class));
    }

    @Test void explicitByokSkipsRouterAndKeepsUserModel() throws Exception {
        enabled();configure("local");
        var profiles=mock(ModelProfileService.class);var factory=mock(PrivateChatModels.class);var chosen=mock(ChatModel.class);
        var snapshot=new ModelProfileService.Snapshot("user-selected","User model","openai",url()+"/v1","user-model","openai-completions");
        var selected=new ModelProfileService.Resolved(snapshot,PRIVATE_KEY,true);
        when(profiles.resolve("user-selected",null)).thenReturn(selected);when(factory.create(selected)).thenReturn(chosen);
        when(chosen.call(any(Prompt.class))).thenReturn(PartnerChatServiceTest.answer("user answer"));
        var byok=createChat(profiles,factory);var in=request("quality");
        var input=new PartnerChatService.Input(in.conversationId(),in.requestId(),0,in.text(),"auto",List.of(),"user-selected","chat",null,false,null,"quality");
        var turn=byok.send("office",input).turns().getLast();assertThat(turn.status()).isEqualTo("SUCCEEDED");
        assertThat(turn.answer()).isEqualTo("user answer");assertThat(turn.modelSnapshot()).isEqualTo(snapshot);assertThat(turn.routerDecision()).isNull();
        assertThat(routerCalls).hasValue(0);verify(chosen,times(1)).call(any(Prompt.class));verify(defaultModel,never()).call(any(Prompt.class));
    }

    @ParameterizedTest @ValueSource(strings={"local","flash","pro"})
    void configuredTiersAreCalledGovernedPersistedAndRestored(String tier) throws Exception {
        enabled();configure(tier);replyTier=tier;replyModel=Map.of("local",LOCAL,"flash",FLASH,"pro",PRO).get(tier);
        var input=request("balanced");var saved=chat.send("office",input);var turn=saved.turns().getLast();
        assertThat(turn.status()).isEqualTo("SUCCEEDED");assertThat(turn.answer()).isEqualTo("tier answer fixture-"+tier);
        assertThat(turn.routerDecision().applied()).isTrue();assertThat(turn.routerDecision().tier()).isEqualTo(tier);
        assertThat(turn.routerDecision().chosenModel()).isEqualTo(replyModel);assertThat(turn.modelSnapshot().model()).isEqualTo("fixture-"+tier);
        assertThat(tierRequests.element().path("model").asText()).isEqualTo("fixture-"+tier);assertThat(tierCalls).hasValue(1);
        assertThat(governance.events()).hasSize(1);assertThat(governance.events().getFirst().status()).isEqualTo("SUCCEEDED");
        Path file=root.resolve("work").resolve(TaskWorkflowTest.A.id()).resolve("conversations").resolve(saved.id()+".json");
        assertThat(json.readTree(file.toFile()).path("turns").get(0).path("routerDecision").path("applied").asBoolean()).isTrue();
        assertThat(Files.readString(file)).doesNotContain(PRIVATE_KEY,"apiKey");
        var restored=createChat(null,privateModels);assertThat(restored.get("office",saved.id())).isEqualTo(saved);
        assertThat(chat.send("office",input)).isEqualTo(saved);assertThat(routerCalls).hasValue(1);assertThat(tierCalls).hasValue(1);
        verify(defaultModel,never()).call(any(Prompt.class));
    }

    @Test void shadowCapturesFullDecisionButCallsDefaultModel() throws Exception {
        enabled();configure("local");properties.setShadowOnly(true);var turn=send("economy");var decision=turn.routerDecision();
        assertThat(turn.status()).isEqualTo("SUCCEEDED");assertThat(turn.answer()).isEqualTo("default answer");
        assertThat(decision.shadowOnly()).isTrue();assertThat(decision.applied()).isFalse();assertThat(decision.intent()).isEqualTo("general_chat_qa");
        assertThat(decision.rawModel()).isEqualTo(LOCAL);assertThat(decision.jointConfidence()).isEqualTo(0.9);assertThat(decision.candidateProbs()).hasSize(4);
        assertThat(routerCalls).hasValue(1);assertThat(tierCalls).hasValue(0);verify(defaultModel,times(1)).call(any(Prompt.class));
    }

    @ParameterizedTest @ValueSource(strings={"economy","balanced","quality"})
    void budgetsPassThroughAndPersist(String budget) throws Exception {
        enabled();var turn=send(budget);assertThat(turn.status()).isEqualTo("SUCCEEDED");
        assertThat(routerRequests.element().path("budget_mode").asText()).isEqualTo(budget);
        assertThat(routerRequests.element().path("request").asText()).isEqualTo(PRIVATE_PROMPT);
        assertThat(turn.routerDecision().budgetMode()).isEqualTo(budget);
    }

    @Test void unconfiguredTierFallsBackWithoutCallingAnyTier() throws Exception {
        enabled();assertFallback("tier_unconfigured");assertThat(tierCalls).hasValue(0);
    }

    @Test void unexpectedClientExceptionCannotEscapeIntoPartnerBusiness() throws Exception {
        enabled();var brokenClient=mock(SmartRouterClient.class);
        when(brokenClient.route(anyString(),anyString())).thenThrow(new IllegalStateException(PRIVATE_PROMPT+PRIVATE_KEY));
        router=new SmartRouterService(properties,brokenClient,privateModels);chat.setSmartRouter(router);
        var turn=send("balanced");assertThat(turn.status()).isEqualTo("SUCCEEDED");assertThat(turn.answer()).isEqualTo("default answer");
        assertThat(turn.routerDecision().applied()).isFalse();
        assertThat(json.writeValueAsString(turn.routerDecision())+json.writeValueAsString(router.status())).doesNotContain(PRIVATE_PROMPT,PRIVATE_KEY);
    }

    @Test void concurrentDecisionsKeepAggregateCountsExact() throws Exception {
        enabled();properties.setShadowOnly(true);properties.setTimeoutMs(5000);
        try(var executor=Executors.newFixedThreadPool(6)) {
            var jobs=new ArrayList<Callable<SmartRouterService.RouteOutcome>>();
            for(int i=0;i<24;i++)jobs.add(()->router.route(PRIVATE_PROMPT,"chat","balanced",defaultModel));
            for(var result:executor.invokeAll(jobs)) {assertThat(result.get().model()).isSameAs(defaultModel);assertThat(result.get().decision().intent()).isEqualTo("general_chat_qa");}
        }
        var status=json.valueToTree(router.status());assertThat(status.path("totalDecisions").asLong()).isEqualTo(24);
        assertThat(status.path("intentCounts").path("general_chat_qa").asLong()).isEqualTo(24);assertThat(status.path("tierCounts").path("local").asLong()).isEqualTo(24);
        assertThat(routerCalls).hasValue(24);assertThat(tierCalls).hasValue(0);
    }
    void assertFallback(String reason) throws Exception {
        var turn=send("balanced");assertThat(turn.status()).isEqualTo("SUCCEEDED");assertThat(turn.answer()).isEqualTo("default answer");
        assertThat(turn.routerDecision()).isNotNull();assertThat(turn.routerDecision().applied()).isFalse();assertThat(turn.routerDecision().fallbackReason()).isEqualTo(reason);
        verify(defaultModel,atLeastOnce()).call(any(Prompt.class));
    }

    @Test void refusedConnectionFallsBackAsNetworkError() throws Exception {
        enabled();int closedPort;try(var socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){closedPort=socket.getLocalPort();}
        properties.setBaseUrl("http://127.0.0.1:"+closedPort);assertFallback("network_error");assertThat(routerCalls).hasValue(0);
    }
    @Test void responseTimeoutFallsBackWithoutFailingBusiness() throws Exception {
        enabled();properties.setTimeoutMs(50);delayMs=300;assertFallback("timeout");
    }
    @Test void stalledResponseBodyIsAlsoBoundedByTimeout() throws Exception {
        enabled();properties.setTimeoutMs(100);delayMs=500;delayedBody=true;
        long start=System.nanoTime();assertFallback("timeout");assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
    }
    @Test void http500FallsBackAndDoesNotExposeProviderError() throws Exception {
        enabled();replyStatus=500;brokenBody="private provider failure "+PRIVATE_KEY;assertFallback("http_status_500");
        assertThat(json.writeValueAsString(router.status())).doesNotContain(PRIVATE_KEY,"private provider failure");
    }
    @ParameterizedTest @ValueSource(strings={"{broken","{\"intent\":","[]","null"})
    void malformedOrNonObjectJsonFailsClosed(String body) throws Exception {
        enabled();brokenBody=body;assertFallback(body.equals("[]")||body.equals("null")?"invalid_schema":"invalid_response");
    }
    @ParameterizedTest @ValueSource(strings={"unknown_model","confidence_high","confidence_negative","confidence_string","empty_intent","wrong_tier"})
    void invalidSchemaFailsClosed(String fault) throws Exception {
        enabled();var node=validResponse("balanced");
        switch(fault){
            case "unknown_model" -> node.put("chosen_model","private-unknown-model");
            case "confidence_high" -> node.put("joint_confidence",1.01);
            case "confidence_negative" -> node.put("route_confidence",-0.01);
            case "confidence_string" -> node.put("joint_confidence","0.8");
            case "empty_intent" -> node.put("intent","");
            case "wrong_tier" -> node.put("tier","pro");
            default -> throw new AssertionError(fault);
        }
        brokenBody=json.writeValueAsString(node);assertFallback("invalid_schema");
    }
    @Test void oversizedBodyIsRejectedBeforeParsing() throws Exception {
        enabled();brokenBody=" ".repeat(65537);assertFallback("invalid_response");
    }
    @Test void abstainFallsBackToDefaultModel() throws Exception {
        enabled();replyModel="abstain";replyTier="abstain";assertFallback("router_abstain");assertThat(tierCalls).hasValue(0);
    }

    @Test void thirdFailureOpensCircuitAndCooldownSuccessClearsIt() throws Exception {
        enabled();properties.setCircuitFailureThreshold(3);properties.setCircuitCooldownMs(30000);
        var clock=new MutableClock();router=new SmartRouterService(properties,newClient(),privateModels,clock);chat.setSmartRouter(router);
        replyStatus=500;
        for(int i=0;i<3;i++)assertFallback("http_status_500");
        assertThat(routerCalls).hasValue(3);assertThat(router.status()).containsEntry("circuitOpen",true);
        long start=System.nanoTime();var fast=router.route(PRIVATE_PROMPT,"chat","balanced",defaultModel);long elapsed=System.nanoTime()-start;
        assertThat(fast.model()).isSameAs(defaultModel);assertThat(fast.decision().fallbackReason()).isEqualTo("circuit_open");
        assertThat(elapsed/1_000_000.0).isLessThan(50);assertThat(routerCalls).hasValue(3);
        assertFallback("circuit_open");assertThat(routerCalls).hasValue(3);
        clock.advance(30001);replyStatus=200;properties.setShadowOnly(true);
        assertThat(send("balanced").status()).isEqualTo("SUCCEEDED");assertThat(routerCalls).hasValue(4);assertThat(router.status()).containsEntry("circuitOpen",false);
        replyStatus=500;assertFallback("http_status_500");assertThat(router.status()).containsEntry("circuitOpen",false);
    }
    static final class MutableClock extends Clock {
        long millis=1_000_000;void advance(long delta){millis+=delta;}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(millis);}public long millis(){return millis;}
    }

    @Test void legacyConversationWithoutRouterFieldRestoresWithoutRewriting() throws Exception {
        var input=request(null);var saved=chat.send("office",input);
        Path file=root.resolve("work").resolve(TaskWorkflowTest.A.id()).resolve("conversations").resolve(saved.id()+".json");
        var tree=(ObjectNode)json.readTree(file.toFile());((ObjectNode)tree.path("turns").get(0)).remove("routerDecision");json.writeValue(file.toFile(),tree);
        byte[] before=Files.readAllBytes(file);var restored=createChat(null,privateModels);var result=restored.get("office",saved.id());
        assertThat(result).isEqualTo(saved);assertThat(result.turns().getLast().routerDecision()).isNull();assertThat(Files.readAllBytes(file)).isEqualTo(before);
        enabled();assertThat(restored.send("office",input)).isEqualTo(saved);assertThat(routerCalls).hasValue(0);
        var turn=(ObjectNode)tree.path("turns").get(0);turn.put("futureSafeField",true);
        assertThat(json.treeToValue(turn,PartnerChatService.Turn.class).answer()).isEqualTo("default answer");
    }

    @Test void budgetIdempotencyRejectsChangedExplicitBudgetAndPreservesOmittedBudget() throws Exception {
        enabled();var in=request("economy");var saved=chat.send("office",in);
        var changed=new PartnerChatService.Input(in.conversationId(),in.requestId(),0,in.text(),"auto",List.of(),"system","chat",null,false,null,"quality");
        assertThatThrownBy(()->chat.send("office",changed)).isInstanceOf(IllegalStateException.class);
        var omitted=new PartnerChatService.Input(in.conversationId(),in.requestId(),0,in.text(),"auto",List.of(),"system","chat",null,false);
        assertThat(chat.send("office",omitted)).isEqualTo(saved);assertThat(routerCalls).hasValue(1);
    }

    @Test void statusCapabilitiesAndLogsExposeOnlySafeMetadata() throws Exception {
        enabled();properties.setShadowOnly(true);Logger logger=(Logger)LoggerFactory.getLogger(SmartRouterService.class);
        var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        try {
            send("quality");replyStatus=500;brokenBody=PRIVATE_PROMPT+PRIVATE_KEY;send("balanced");
            @SuppressWarnings("unchecked") ObjectProvider<KnowledgeService> knowledge=mock(ObjectProvider.class);
            @SuppressWarnings("unchecked") ObjectProvider<SyncMcpToolCallbackProvider> mcp=mock(ObjectProvider.class);
            var caps=new CapabilitiesController(knowledge,mcp,new ToolCallback[0]);caps.setSmartRouter(router);
            var mvc=MockMvcBuilders.standaloneSetup(new RouterController(router),caps).build();
            String statusBody=mvc.perform(get("/api/ai/router/status").contextPath("/api")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var status=json.readTree(statusBody);assertThat(status.path("enabled").asBoolean()).isTrue();assertThat(status.path("shadowOnly").asBoolean()).isTrue();
            assertThat(status.path("totalDecisions").asLong()).isEqualTo(2);assertThat(status.path("appliedCount").asLong()).isZero();
            for(String key:List.of("defaultBudgetMode","budgetModes","circuitOpen","fallbackCount","avgLatencyMs","tierCounts","intentCounts","fallbackReasons"))assertThat(status.has(key)).as(key).isTrue();
            String capsBody=mvc.perform(get("/api/ai/capabilities").contextPath("/api")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var capability=json.readTree(capsBody);assertThat(capability.path("router").path("enabled").asBoolean()).isTrue();
            assertThat(capability.path("router").path("budgetModes")).hasSize(3);assertThat(capability.path("router").path("defaultBudgetMode").asText()).isEqualTo("balanced");
            assertThat(capability.path("rag").asBoolean()).isFalse();assertThat(capability.path("terminal").asBoolean()).isFalse();assertThat(capability.path("tools")).isEmpty();assertThat(capability.path("mcpTools")).isEmpty();
            assertThat(statusBody+capsBody).doesNotContain(PRIVATE_PROMPT,PRIVATE_KEY,"apiKey","baseUrl");
            assertThat(appender.list).isNotEmpty().allSatisfy(event->{assertThat(event.getFormattedMessage()).doesNotContain(PRIVATE_PROMPT,PRIVATE_KEY);assertThat(event.getThrowableProxy()).isNull();});
            assertThat(appender.list.getFirst().getFormattedMessage()).contains("intent=","chosen_model=","tier=","confidence=","router_latency_ms=","budget_mode=","applied=","shadow_only=","fallback_reason=");
        } finally {logger.detachAppender(appender);appender.stop();}
    }

    @Test void echoedIntentAndFallbackTextCannotLeakThroughLogsOrStatus() throws Exception {
        enabled();properties.setShadowOnly(true);Logger logger=(Logger)LoggerFactory.getLogger(SmartRouterService.class);
        var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        try {
            var poisoned=validResponse("balanced");poisoned.put("intent",PRIVATE_PROMPT);brokenBody=json.writeValueAsString(poisoned);
            assertFallback("invalid_schema");
            poisoned=validResponse("balanced");poisoned.put("fallback_reason",PRIVATE_PROMPT+PRIVATE_KEY);brokenBody=json.writeValueAsString(poisoned);
            var turn=send("balanced");assertThat(turn.status()).isEqualTo("SUCCEEDED");
            assertThat(json.writeValueAsString(turn.routerDecision())+json.writeValueAsString(router.status())).doesNotContain(PRIVATE_PROMPT,PRIVATE_KEY);
            assertThat(appender.list).hasSizeGreaterThanOrEqualTo(2).allSatisfy(event->{
                assertThat(event.getFormattedMessage()).doesNotContain(PRIVATE_PROMPT,PRIVATE_KEY);assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {logger.detachAppender(appender);appender.stop();}
    }

    @Test void configurationClampsTimeoutNormalizesBudgetAndRejectsCredentialUrls() {
        properties.setTimeoutMs(1);assertThat(properties.getTimeoutMs()).isEqualTo(50);properties.setTimeoutMs(99999);assertThat(properties.getTimeoutMs()).isEqualTo(5000);
        properties.setDefaultBudgetMode("not-a-mode");assertThat(router.defaultBudgetMode()).isEqualTo("balanced");
        for(String url:List.of("http://user:password@localhost:18129","file:///private","http://localhost:18129/?key=private","http://localhost:18129/#private"))
            assertThatThrownBy(()->properties.setBaseUrl(url)).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("password").hasMessageNotContaining("private");
        assertThat(routerCalls).hasValue(0);
    }
}
