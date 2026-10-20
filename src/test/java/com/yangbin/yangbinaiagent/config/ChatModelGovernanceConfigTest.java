package com.yangbin.yangbinaiagent.config;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.yangbin.yangbinaiagent.governance.ModelGovernance;
import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ChatModelGovernanceConfigTest {
 @Test void dashScopeAutoConfiguredRetryCannotBypassAdmission(){var retry=RetryTemplate.builder().maxAttempts(5).build();var model=DashScopeChatModel.builder().dashScopeApi(mock(DashScopeApi.class)).retryTemplate(retry).build();var governance=mock(ModelGovernance.class);when(governance.govern(model,"system-dashscope","system")).thenReturn(model);assertThat(new ChatModelConfig().dashscopeAppChatModel(model,"fixture-key",governance)).isSameAs(model);AtomicInteger attempts=new AtomicInteger();assertThatThrownBy(()->model.retryTemplate.execute(context->{attempts.incrementAndGet();throw new IllegalStateException("fixture failure");})).isInstanceOf(IllegalStateException.class);assertThat(attempts).hasValue(1);verify(governance).govern(model,"system-dashscope","system");}
 @Test void sharedOpenAiErrorHandlerNeverReadsProviderErrorBody()throws Exception{var response=mock(org.springframework.http.client.ClientHttpResponse.class);when(response.getStatusCode()).thenReturn(org.springframework.http.HttpStatus.UNAUTHORIZED);var handler=ChatModelConfig.safeErrors();assertThat(handler.hasError(response)).isTrue();assertThatThrownBy(()->handler.handleError(response)).isInstanceOf(java.io.IOException.class).hasNoCause().hasMessageNotContaining("fixture-key");verify(response,never()).getBody();}

}
