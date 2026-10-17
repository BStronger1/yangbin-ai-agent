package com.yangbin.yangbinaiagent.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;

/** DashScope eagerly requires a nonempty key even when only personal BYOK models are used. */
public final class MissingDashScopeKeyEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    static final String PROPERTY = "spring.ai.dashscope.api-key";
    static final String PLACEHOLDER = "development-placeholder";
    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configured = environment.getProperty(PROPERTY);
        if (configured == null || configured.isBlank()) {
            // ChatModelConfig recognizes this sentinel and refuses model requests before any network call.
            environment.getPropertySources().addFirst(new MapPropertySource(
                    "unconfiguredDashScopeKey", Map.of(PROPERTY, PLACEHOLDER)));
        }
    }
    @Override public int getOrder() {
        // Run after application.yml and its environment placeholders have been loaded.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
