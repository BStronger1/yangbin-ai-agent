package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import com.yangbin.yangbinaiagent.models.*;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class ModelSettingsTest {
    @TempDir Path root; final ObjectMapper json=new ObjectMapper(); ModelProfileService service; UserContext.Scope scope; HttpServer server;
    final AtomicInteger requests=new AtomicInteger(); final AtomicReference<String> response=new AtomicReference<>(); final AtomicInteger status=new AtomicInteger(200);
    final AtomicReference<CountDownLatch> entered=new AtomicReference<>(),release=new AtomicReference<>();
    final List<String> queries=new CopyOnWriteArrayList<>(),auth=new CopyOnWriteArrayList<>();
    static final String KEY="fixture-key-not-for-public-output";
    @BeforeEach void setup()throws Exception{
        scope=UserContext.open(UserContext.ADMIN);service=new ModelProfileService(json,root.toString(),"");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{if(entered.get()!=null){entered.get().countDown();try{if(!release.get().await(5,TimeUnit.SECONDS))throw new java.io.IOException("fixture timed out");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.IOException("interrupted");}}requests.incrementAndGet();queries.add(e.getRequestURI().toString());auth.add(Objects.toString(e.getRequestHeaders().getFirst("Authorization"),e.getRequestHeaders().getFirst("x-api-key")));if(status.get()==302)e.getResponseHeaders().add("Location",base()+"/redirect");byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(status.get(),bytes.length);try(var out=e.getResponseBody()){out.write(bytes);}});server.start();response.set("{\"data\":[{\"id\":\"gpt-5.4\"},{\"id\":\"gpt-4.1\"}]}");
    }
    @AfterEach void close(){server.stop(0);scope.close();}
    String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    String connect(String provider)throws Exception{return service.createConnection(new ModelProfileService.ConnectionInput("Test service",provider,base(),KEY)).connections().get(0).id();}
    @Test void discoversOncePerClickSharesEncryptedKeyAndExplicitlySelectsOnlyTwo()throws Exception{
        String id=connect("openai");var catalog=service.discover(id);assertThat(requests.get()).isEqualTo(1);assertThat(auth).containsExactly("Bearer "+KEY);
        assertThat(catalog.allProfiles()).hasSize(2);assertThat(catalog.profiles()).isEmpty();assertThat(catalog.systemEnabled()).isTrue();
        var ids=catalog.allProfiles().stream().map(ModelProfileService.Profile::id).toList();catalog=service.selection(ids);
        assertThat(catalog.profiles()).hasSize(2);assertThat(catalog.systemEnabled()).isFalse();assertThat(catalog.defaultModelId()).isEqualTo(ids.get(0));
        assertThatThrownBy(()->service.resolve("system")).hasMessageContaining("未在总设置");assertThatThrownBy(()->service.defaults("system")).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.resolve(ids.get(0),"high").snapshot().reasoningEffort()).isEqualTo("high");
        assertThatThrownBy(()->service.resolve(ids.get(1),"high")).hasMessageContaining("不支持");
        assertThat(json.writeValueAsString(catalog)).doesNotContain(KEY,"ciphertext","apiKey");
        String stored=Files.readString(root.resolve(UserContext.ADMIN.id()).resolve("profiles.json"));assertThat(stored).doesNotContain(KEY);
        JsonNode disk=json.readTree(stored);assertThat(disk.path("connections").get(0).path("ciphertext").asText()).isNotBlank();for(JsonNode p:disk.path("profiles"))assertThat(p.path("ciphertext").isNull()).isTrue();
        service.discover(id);assertThat(service.catalog().profiles().stream().map(ModelProfileService.Profile::id)).containsExactlyElementsOf(ids);
    }
    @Test void existingStandaloneConfigsKeepIdsDefaultAndKeyAcrossSelectionMigration()throws Exception{
        var old=service.create(new ModelProfileService.Input("Old","openai",base(),"gpt-5.4",KEY));service.defaults(old.id());
        String id=connect("openai");service.discover(id);assertThat(service.catalog().profiles()).extracting(ModelProfileService.Profile::id).containsExactly(old.id());assertThat(service.selectedId(null)).isEqualTo(old.id());
        var restored=new ModelProfileService(json,root.toString(),"");assertThat(restored.resolve(old.id()).apiKey()).isEqualTo(KEY);
        assertThatThrownBy(()->restored.selection(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void manualConnectionModelDoesNotNeedAnotherKeyAndBlankUpdateRetainsIt()throws Exception{
        String id=connect("openai-compatible");var p=service.create(new ModelProfileService.Input("Manual",null,null,"custom-model",null,id));
        service.selection(List.of(p.id()));assertThat(service.resolve(p.id()).apiKey()).isEqualTo(KEY);service.updateConnection(id,new ModelProfileService.ConnectionInput("Renamed","openai-compatible",base(),""));assertThat(service.resolve(p.id()).apiKey()).isEqualTo(KEY);
        service.updateConnection(id,new ModelProfileService.ConnectionInput("Renamed","openai-compatible",base(),"rotated-fixture-key"));assertThat(service.resolve(p.id()).apiKey()).isEqualTo("rotated-fixture-key");
    }
    @Test void deletionNeverSilentlyEnablesSystemAndRetainsHistoricProfileMetadata()throws Exception{
        String id=connect("openai");var p=service.discover(id).allProfiles().get(0);service.selection(List.of(p.id()));var snapshot=service.resolve(p.id()).snapshot();var catalog=service.deleteConnection(id);
        assertThat(catalog.systemEnabled()).isFalse();assertThat(catalog.defaultModelId()).isNull();assertThat(catalog.profiles()).isEmpty();assertThatThrownBy(()->service.resolve(p.id())).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(root.resolve(UserContext.ADMIN.id()).resolve("profiles.json"))).contains(p.id()).doesNotContain(KEY);assertThat(snapshot.model()).isEqualTo("gpt-5.4");service.selection(List.of("system"));assertThat(service.resolve(null)).isNull();
    }
    @Test void ownerIsolationPreventsDiscoveryAndCredentialReuse()throws Exception{
        String id=connect("openai");try(var other=UserContext.open(TaskWorkflowTest.A)){
            assertThat(service.catalog().connections()).isEmpty();assertThatThrownBy(()->service.discover(id)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->service.deleteConnection(id)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->service.create(new ModelProfileService.Input("bad",null,null,"m",null,id))).isInstanceOf(IllegalArgumentException.class);
        }assertThat(requests.get()).isZero();
    }
    @Test void nonAdminRejectsPrivateEndpointsBeforeCredentialsAreSent()throws Exception{
        try(var user=UserContext.open(TaskWorkflowTest.A)){for(String url:List.of(base(),"https://127.0.0.1","https://169.254.169.254","https://user:password@8.8.8.8"))assertThatThrownBy(()->service.createConnection(new ModelProfileService.ConnectionInput("bad","openai",url,KEY))).isInstanceOf(IllegalArgumentException.class);}
        assertThat(requests.get()).isZero();
    }
    @Test void redirectsErrorsAndSupplierSecretEchoAreRejectedWithoutPartialSave()throws Exception{
        String id=connect("openai");status.set(302);response.set(KEY);assertThatThrownBy(()->service.discover(id)).hasMessageNotContaining(KEY);assertThat(requests.get()).isEqualTo(1);
        status.set(401);assertThatThrownBy(()->service.discover(id)).hasMessageNotContaining(KEY);assertThat(requests.get()).isEqualTo(2);
        status.set(200);response.set("{\"data\":[{\"id\":\""+KEY+"\"}]}");assertThatThrownBy(()->service.discover(id)).hasMessageNotContaining(KEY);assertThat(service.catalog().allProfiles()).isEmpty();
    }
    @Test void malformedOrOversizedResponsesFailClosed()throws Exception{
        String id=connect("openai");for(String body:List.of("not-json", "{\"models\":[]}","{\"data\":[{\"id\":\"\"}]}","x".repeat(2*1024*1024+1))){response.set(body);assertThatThrownBy(()->service.discover(id)).isInstanceOf(java.io.IOException.class);}
        assertThat(service.catalog().allProfiles()).isEmpty();
    }
    @Test void paginationRepeatsFailWithoutClaimingCompleteCatalog()throws Exception{
        String id=connect("anthropic");response.set("{\"data\":[{\"id\":\"claude-opus-4-6\"}],\"has_more\":true,\"last_id\":\"same\"}");
        assertThatThrownBy(()->service.discover(id)).isInstanceOf(java.io.IOException.class);assertThat(requests.get()).isEqualTo(2);assertThat(queries).containsExactly("/v1/models?limit=100","/v1/models?limit=100&after_id=same");assertThat(auth).containsOnly(KEY);assertThat(service.catalog().allProfiles()).isEmpty();
    }
    @Test void discoveryCapabilityMetadataLimitsActualAllowedEffort()throws Exception{
        response.set("{\"data\":[{\"id\":\"future-deepseek\",\"effort\":{\"supported_levels\":[\"low\",\"max\",\"made-up\"]}}]}");
        String id=connect("deepseek");var p=service.discover(id).allProfiles().get(0);assertThat(p.reasoningEfforts()).containsExactly("none","low","max");service.selection(List.of(p.id()));assertThatThrownBy(()->service.resolve(p.id(),"high")).isInstanceOf(IllegalArgumentException.class);assertThat(service.resolve(p.id(),"max").snapshot().reasoningEffort()).isEqualTo("max");
        assertThat(ModelReasoning.supported("anthropic","unknown",json.readTree("{\"capabilities\":{\"effort\":{\"low\":{\"supported\":true},\"xhigh\":{\"supported\":true}}}}"))).containsExactly("low","xhigh");
    }
    @Test void removedDiscoveryModelIsDisabledButRetainedForHistory()throws Exception{
        String id=connect("openai");var p=service.discover(id).allProfiles().get(0);service.selection(List.of(p.id()));response.set("{\"data\":[]}");service.discover(id);
        assertThat(service.catalog().profiles()).isEmpty();assertThat(service.catalog().systemEnabled()).isFalse();assertThatThrownBy(()->service.resolve(p.id())).isInstanceOf(IllegalArgumentException.class);assertThat(Files.readString(root.resolve(UserContext.ADMIN.id()).resolve("profiles.json"))).contains(p.id());
    }
    @Test void providerPayloadUsesRealFieldsAndDefaultsRemainUntouched()throws Exception{
        var payload=json.createObjectNode();ModelReasoning.apply(payload,new ModelProfileService.Snapshot("id","n","openai","url","gpt-5.4","api","high"));assertThat(payload.path("reasoning_effort").asText()).isEqualTo("high");
        payload=json.createObjectNode();ModelReasoning.apply(payload,new ModelProfileService.Snapshot("id","n","deepseek","url","m","api","none"));assertThat(payload.path("thinking").path("type").asText()).isEqualTo("disabled");assertThat(payload.has("reasoning_effort")).isFalse();
        payload=json.createObjectNode();ModelReasoning.apply(payload,new ModelProfileService.Snapshot("id","n","deepseek","url","m","api","max"));assertThat(payload.path("thinking").path("type").asText()).isEqualTo("enabled");assertThat(payload.path("reasoning_effort").asText()).isEqualTo("max");
        payload=json.createObjectNode();ModelReasoning.apply(payload,new ModelProfileService.Snapshot("id","n","anthropic","url","m","api","medium"));assertThat(payload.path("output_config").path("effort").asText()).isEqualTo("medium");assertThat(payload.has("thinking")).isFalse();
        payload=json.createObjectNode();ModelReasoning.apply(payload,new ModelProfileService.Snapshot("id","n","openai","url","m","api"));assertThat(payload.isEmpty()).isTrue();
        assertThat(ModelReasoning.supported("openai-compatible","gpt-5.4",null)).isEmpty();assertThat(ModelReasoning.supported("openai","gpt-5.4",null)).contains("high");assertThat(ModelReasoning.codingSupported("openai","gpt-6.1-sol")).isFalse();assertThat(ModelReasoning.codingSupported("openai","gpt-6-astra-2026-10-20")).isFalse();
    }
    @Test void slowDiscoveryDoesNotBlockCatalogAndCannotOverwriteChangedConnection()throws Exception{
        String id=connect("openai");entered.set(new CountDownLatch(1));release.set(new CountDownLatch(1));
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            Future<ModelProfileService.Catalog> discovering=executor.submit(()->{try(var owner=UserContext.open(UserContext.ADMIN)){return service.discover(id);}});
            assertThat(entered.get().await(3,TimeUnit.SECONDS)).isTrue();
            Future<ModelProfileService.Catalog> independent=executor.submit(()->{try(var other=UserContext.open(TaskWorkflowTest.A)){return service.catalog();}});
            assertThat(independent.get(1,TimeUnit.SECONDS).connections()).isEmpty();
            service.updateConnection(id,new ModelProfileService.ConnectionInput("Updated","openai",base(),"updated-key"));release.get().countDown();
            assertThatThrownBy(()->discovering.get(3,TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);assertThat(service.catalog().allProfiles()).isEmpty();
        }finally{release.get().countDown();}
    }
    @Test void httpContractUsesCatalogAndRejectsDisabledDefaultAndForeignIds()throws Exception{
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.yangbin.yangbinaiagent.controller.ModelProfileController(service)).build();
        String request=json.writeValueAsString(new ModelProfileService.ConnectionInput("Settings","openai",base(),KEY));
        String body=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/ai/models/connections").contentType("application/json").content(request)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(KEY,"ciphertext");String connection=json.readTree(body).path("connections").get(0).path("id").asText();
        body=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/ai/models/connections/"+connection+"/discover")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();String profile=json.readTree(body).path("allProfiles").get(0).path("id").asText();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/ai/models/selection").contentType("application/json").content(json.writeValueAsString(Map.of("modelProfileIds",List.of(profile))))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/ai/models/defaults").contentType("application/json").content("{\"modelProfileId\":\"system\"}")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        try(var other=UserContext.open(TaskWorkflowTest.A)){mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/ai/models/connections/"+connection+"/discover")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());}
    }

}
