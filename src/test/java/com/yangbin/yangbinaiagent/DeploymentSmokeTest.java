package com.yangbin.yangbinaiagent;

import com.sun.net.httpserver.HttpServer;
import com.yangbin.yangbinaiagent.tools.PDFGenerationTool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=prod", "app.model.provider=openai", "app.model.api-key=test-key",
        "app.model.name=test-model", "app.rag.enabled=false", "app.rag.cloud-enabled=false"
})
class DeploymentSmokeTest {
    com.yangbin.yangbinaiagent.security.UserContext.Scope identity;
    @org.junit.jupiter.api.BeforeEach void identity(){identity=com.yangbin.yangbinaiagent.security.UserContext.open(com.yangbin.yangbinaiagent.security.UserContext.ADMIN);}
    @org.junit.jupiter.api.AfterEach void clearIdentity(){identity.close();}
    static final java.util.concurrent.atomic.AtomicInteger answerAndTerminateRequests = new java.util.concurrent.atomic.AtomicInteger();
    static final HttpServer provider;
    static {
        try {
            provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            provider.createContext("/v1/chat/completions", exchange -> {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String body;
                if (request.contains("\"stream\":true")) {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    body = "data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"stream-ok\"},\"finish_reason\":null}]}\n\n"
                            + "data: [DONE]\n\n";
                } else {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    String msg = request.contains("terminate-test")
                            ? "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"doTerminate\",\"arguments\":\"{}\"}}]}"
                            : "{\"role\":\"assistant\",\"content\":\"sync-ok\"}";
                    if (request.contains("answer-and-terminate-test")) {
                        answerAndTerminateRequests.incrementAndGet();
                        msg = msg.replace("\"content\":null", "\"content\":\"M017\"");
                    }
                    body = "{\"id\":\"test\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":" + msg + ",\"finish_reason\":\"stop\"}]}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            provider.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.model.base-url", () -> "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1");
    }
    @AfterAll static void stopProvider() { provider.stop(0); }
    @Autowired TestRestTemplate http;

    @Test void startsWithoutDatabaseRagOrMcp() {
        assertThat(http.getForObject("/health", String.class)).isEqualTo("ok");
    }
    @Test void compatibleChatAndStreamComplete() {
        assertThat(http.getForObject("/ai/love_app/chat/sync?message=hello&chatId=test-sync", String.class)).isEqualTo("sync-ok");
        String stream = http.getForObject("/ai/love_app/chat/sse?message=hello&chatId=test-stream", String.class);
        assertThat(stream).contains("stream-ok", "[DONE]");
    }
    @Test void agentExecutesRegisteredTerminationTool() {
        String response = http.getForObject("/ai/manus/chat?message=terminate-test", String.class);
        assertThat(response).contains("doTerminate", "[DONE]").doesNotContain("执行错误");
    }
    @Test void agentPreservesAnswerAlongsideTerminationToolInRealSse() {
        answerAndTerminateRequests.set(0);
        String response = http.getForObject("/ai/manus/chat?message=answer-and-terminate-test", String.class);
        assertThat(response).containsOnlyOnce("M017").contains("doTerminate", "[DONE]")
                .doesNotContain("执行错误", "Step 2");
        assertThat(response.indexOf("M017")).isLessThan(response.indexOf("[DONE]"));
        assertThat(answerAndTerminateRequests.get()).isEqualTo(1);
    }
    @Test void agentReturnsAnswerWithoutRepeatingTwentyTimes() {
        String response = http.getForObject("/ai/manus/chat?message=hello", String.class);
        assertThat(response).contains("sync-ok", "[DONE]").doesNotContain("Step 2");
    }
    @Test void chinesePdfDownloadsAndRejectsTraversal() throws Exception {
        PDFGenerationTool tool = new PDFGenerationTool();
        String name = "smoke-" + UUID.randomUUID() + ".pdf";
        Path generated = Path.of("tmp", "pdf","admin", name);
        try {
            assertThat(tool.generatePDF(name, "中文部署验收 PDF")).contains("/api/files/" + name);
            var response = http.getForEntity("/files/" + name, byte[].class);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(new String(response.getBody(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
            assertThat(tool.generatePDF("../escape.pdf", "test")).startsWith("Error:");
        } finally { Files.deleteIfExists(generated); }
    }
}
