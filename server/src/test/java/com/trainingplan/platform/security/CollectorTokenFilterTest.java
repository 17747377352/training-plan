package com.trainingplan.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainingplan.platform.config.CollectorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内部采集器接口鉴权测试。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
class CollectorTokenFilterTest {

    private static final String TOKEN = "internal-service-token";

    private CollectorTokenFilter filter;

    @BeforeEach
    void setUp() {
        CollectorProperties properties = new CollectorProperties(
                "http://127.0.0.1:8090", TOKEN, Duration.ofSeconds(30));
        filter = new CollectorTokenFilter(properties, new ObjectMapper());
    }

    @Test
    void shouldAllowInternalRequestWithValidToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/collector/jobs/1/session");
        request.addHeader("X-Collector-Token", TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldRejectInternalRequestWithoutToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/collector/jobs/1/session");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("40100");
    }

    @Test
    void shouldRejectInternalRequestWithWrongToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/collector/jobs/1/session");
        request.addHeader("X-Collector-Token", "wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void shouldNotInterceptPublicPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/system/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void shouldFailClosedWhenServerTokenIsBlank() throws Exception {
        CollectorProperties blank = new CollectorProperties(
                "http://127.0.0.1:8090", " ", Duration.ofSeconds(30));
        CollectorTokenFilter blankFilter = new CollectorTokenFilter(blank, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/collector/jobs/1/session");
        request.addHeader("X-Collector-Token", " ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        blankFilter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }
}
