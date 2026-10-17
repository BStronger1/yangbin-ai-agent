package com.yangbin.yangbinaiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yangbin.yangbinaiagent.models.*;
import com.yangbin.yangbinaiagent.security.UserContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetAddress;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ModelProfileServiceTest {
    @TempDir Path root;ObjectMapper json=new ObjectMapper();ModelProfileService service;UserContext.Scope scope;
    @BeforeEach void setup()throws Exception{scope=UserContext.open(TaskWorkflowTest.A);service=new ModelProfileService(json,root.toString(),"");}
    @AfterEach void close(){scope.close();}
    ModelProfileService.Input input(String key){return new ModelProfileService.Input("私有模型","openai-compatible","https://8.8.8.8/gateway/v1","fixture-model",key);}
    @Test void encryptsCredentialsAndKeepsOnlySafeMetadataInApi()throws Exception{
        var profile=service.create(input("private-fixture-secret"));String publicJson=json.writeValueAsString(service.catalog());
        assertThat(publicJson).contains("hasKey","fixture-model").doesNotContain("private-fixture-secret","ciphertext","apiKey");
        String persisted=Files.readString(root.resolve(TaskWorkflowTest.A.id()).resolve("profiles.json"));assertThat(persisted).contains("ciphertext").doesNotContain("private-fixture-secret");
        assertThat(Files.size(root.resolve("master.key"))).isEqualTo(32);
        assertThat(service.resolve(profile.id()).apiKey()).isEqualTo("private-fixture-secret");assertThat(service.resolve(profile.id()).toString()).doesNotContain("private-fixture-secret");
        var restored=new ModelProfileService(json,root.toString(),"");assertThat(restored.resolve(profile.id()).apiKey()).isEqualTo("private-fixture-secret");
    }
    @Test void blankUpdatePreservesKeyRotationReencryptsAndDeleteResetsDefault()throws Exception{
        var p=service.create(input("first-secret"));service.defaults(p.id());assertThat(service.selectedId(null)).isEqualTo(p.id());
        service.update(p.id(),input(""));assertThat(service.resolve(null).apiKey()).isEqualTo("first-secret");
        service.update(p.id(),input("second-secret"));assertThat(service.resolve(null).apiKey()).isEqualTo("second-secret");
        service.delete(p.id());assertThat(service.catalog().defaultModelId()).isEqualTo("system");assertThat(service.resolve(null)).isNull();
    }
    @Test void ownerIsolationAndCiphertextAuthenticationRejectCrossAccountCopy()throws Exception{
        var p=service.create(input("owner-a-secret"));String original=Files.readString(root.resolve(TaskWorkflowTest.A.id()).resolve("profiles.json"));
        try(var other=UserContext.open(TaskWorkflowTest.B)){
            assertThat(service.catalog().profiles()).isEmpty();assertThatThrownBy(()->service.resolve(p.id())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->service.update(p.id(),input("replacement"))).isInstanceOf(IllegalArgumentException.class);
            Path otherFile=root.resolve(TaskWorkflowTest.B.id()).resolve("profiles.json");Files.writeString(otherFile,original);
            assertThatThrownBy(()->service.resolve(p.id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("解密");
        }
    }
    @Test void wrongMasterKeyFailsClosedAndNeverSilentlyReplacesExistingKey()throws Exception{
        var p=service.create(input("secret"));byte[] original=Files.readAllBytes(root.resolve("master.key"));byte[] different=new byte[32];Arrays.fill(different,(byte)7);
        var wrong=new ModelProfileService(json,root.toString(),Base64.getEncoder().encodeToString(different));
        assertThatThrownBy(()->wrong.resolve(p.id())).isInstanceOf(IllegalStateException.class);assertThat(Files.readAllBytes(root.resolve("master.key"))).isEqualTo(original);
    }
    @Test void missingMasterKeyNeverGeneratesReplacementForExistingProfiles()throws Exception{
        service.create(input("existing-secret"));Files.delete(root.resolve("master.key"));
        assertThatThrownBy(()->new ModelProfileService(json,root.toString(),"")).isInstanceOf(java.io.IOException.class).hasMessageContaining("master key is missing");
        assertThat(Files.exists(root.resolve("master.key"))).isFalse();assertThat(input("do-not-log").toString()).doesNotContain("do-not-log");
    }
    @Test void rejectsNonHttpsPrivateNetworkAndUrlCredentialsBeforeSaving()throws Exception{
        for(String url:List.of("http://8.8.8.8/v1","https://127.0.0.1","https://10.0.0.1","https://172.16.0.1","https://169.254.169.254","https://100.64.0.1","https://[::1]","https://[fc00::1]","https://user:pass@example.com","https://8.8.8.8/v1?key=oops","https://8.8.8.8/#fragment","file:///etc/passwd","https://8.8.8.8/a/../v1")){
            assertThatThrownBy(()->service.create(new ModelProfileService.Input("bad","openai",url,"m","key"))).as(url).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(service.catalog().profiles()).isEmpty();
        try(var admin=UserContext.open(UserContext.ADMIN)){var p=service.create(new ModelProfileService.Input("local","anthropic","http://127.0.0.1:19000/gateway","m","key"));assertThat(service.resolve(p.id()).snapshot().api()).isEqualTo("anthropic-messages");}
    }
    @Test void rejectsSpecialNetworkRangesAndAcceptsPublicIps()throws Exception{
        for(String ip:List.of("0.0.0.1","127.0.0.1","100.100.1.1","198.18.0.1","224.0.0.1","::","fe80::1","fd00::1","64:ff9b::a00:1","2002:7f00:1::","::2","2001:db8::1","3fff::1"))assertThat(ModelEndpointPolicy.publicAddress(InetAddress.getByName(ip))).as(ip).isFalse();
        assertThat(ModelEndpointPolicy.publicAddress(InetAddress.getByName("8.8.8.8"))).isTrue();assertThat(ModelEndpointPolicy.publicAddress(InetAddress.getByName("2606:4700:4700::1111"))).isTrue();
    }
}
