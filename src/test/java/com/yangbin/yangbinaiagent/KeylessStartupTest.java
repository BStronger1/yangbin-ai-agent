package com.yangbin.yangbinaiagent;

import com.yangbin.yangbinaiagent.models.UnconfiguredChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real application startup with the registered hook, without a model/network fixture. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=prod", "DASHSCOPE_API_KEY=", "app.model.provider=dashscope",
        "app.model.api-key=", "app.models.master-key=", "app.coding.enabled=false",
        "app.rag.enabled=false", "app.rag.cloud-enabled=false", "spring.ai.mcp.client.enabled=false",
        "app.internal-api-token=", "app.tools.extended-enabled=false", "app.tools.terminal-enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KeylessStartupTest {
    static final Path ROOT = Path.of("target", "keyless-startup-" + UUID.randomUUID()).toAbsolutePath();
    @DynamicPropertySource static void isolatedStores(DynamicPropertyRegistry properties) {
        for (String component : List.of("models", "coding", "memory", "plans", "repairs", "skills", "tasks", "work"))
            properties.add("app." + component + ".dir", () -> ROOT.resolve(component).toString());
        properties.add("app.rag.upload-dir", () -> ROOT.resolve("knowledge").toString());
        properties.add("app.rag.index-dir", () -> ROOT.resolve("rag").toString());
        properties.add("logging.file.name", () -> ROOT.resolve("application.log").toString());
    }
    @Autowired Environment environment;
    @Autowired TestRestTemplate http;
    @Autowired @Qualifier("appChatModel") ChatModel appModel;
    @Test void emptyEnvironmentKeyStartsHttpButCannotSendPlaceholderToProvider() {
        assertThat(environment.getProperty("DASHSCOPE_API_KEY")).isEmpty();
        assertThat(environment.getProperty("spring.ai.dashscope.api-key")).isEqualTo("development-placeholder");
        assertThat(http.getForObject("/health", String.class)).isEqualTo("ok");
        assertThat(appModel).isInstanceOf(UnconfiguredChatModel.class);
        assertThatThrownBy(() -> appModel.call(new Prompt("must not make an external request")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("系统默认模型尚未配置");
    }
}
