package com.yangbin.yangbinaiagent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

class MissingDashScopeKeyEnvironmentPostProcessorTest {
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t"})
    void missingOrBlankKeyAllowsEagerAutoConfiguration(String value) {
        MockEnvironment environment = new MockEnvironment();
        if (value != null) environment.setProperty(MissingDashScopeKeyEnvironmentPostProcessor.PROPERTY, value);
        new MissingDashScopeKeyEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        assertThat(environment.getProperty(MissingDashScopeKeyEnvironmentPostProcessor.PROPERTY))
                .isEqualTo(MissingDashScopeKeyEnvironmentPostProcessor.PLACEHOLDER);
    }
    @Test void configuredKeyIsNeverReplacedOrCopiedToAnotherPropertySource() {
        MockEnvironment environment = new MockEnvironment().withProperty(
                MissingDashScopeKeyEnvironmentPostProcessor.PROPERTY, "offline-configured-key");
        new MissingDashScopeKeyEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        assertThat(environment.getProperty(MissingDashScopeKeyEnvironmentPostProcessor.PROPERTY)).isEqualTo("offline-configured-key");
        assertThat(environment.getPropertySources().contains("unconfiguredDashScopeKey")).isFalse();
    }
}
