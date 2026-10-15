package com.yangbin.yangbinaiagent;
import com.yangbin.yangbinaiagent.config.InternalApiTokenFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.assertThat;
class InternalApiTokenFilterUnitTest {
    @Test void directBackendAccessRequiresInternalToken() throws Exception {
        var filter=new InternalApiTokenFilter("test-internal-token");
        var request=new MockHttpServletRequest("GET","/api/ai/manus/chat");
        var response=new MockHttpServletResponse();var chain=new MockFilterChain();
        filter.doFilter(request,response,chain);
        assertThat(response.getStatus()).isEqualTo(401);assertThat(chain.getRequest()).isNull();
        request=new MockHttpServletRequest("GET","/api/ai/manus/chat");request.addHeader("X-Internal-Token","test-internal-token");request.addHeader("X-User-Id","admin");request.addHeader("X-User-Role","admin");
        response=new MockHttpServletResponse();chain=new MockFilterChain();filter.doFilter(request,response,chain);
        assertThat(response.getStatus()).isEqualTo(200);assertThat(chain.getRequest()).isNotNull();
    }
}
