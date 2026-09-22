package com.trainingplan.platform.client;

import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.config.CollectorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 校验 Collector 调用结果和网络失败分流。 */
class RestGarminAuthClientTest {

    private MockRestServiceServer server;
    private RestGarminAuthClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = spy(RestClient.builder());
        server = MockRestServiceServer.bindTo(builder).build();
        // 保留 MockRestServiceServer 的请求工厂，测试不得访问真实 Collector。
        doReturn(builder).when(builder).requestFactory(any());
        client = new RestGarminAuthClient(builder,
                new CollectorProperties("http://127.0.0.1:18090", "unit-test-token", Duration.ofSeconds(120)));
    }

    @Test
    void returnsCollectorResponse() {
        server.expect(requestTo("http://127.0.0.1:18090/internal/garmin/connect"))
                .andExpect(header("X-Collector-Token", "unit-test-token"))
                .andRespond(withSuccess("{\"status\":\"MFA_REQUIRED\",\"loginSessionId\":\"session-1\"}",
                        MediaType.APPLICATION_JSON));

        CollectorAuthResult result = client.connect("rider@example.com", "secret", "CN");

        assertThat(result.status()).isEqualTo("MFA_REQUIRED");
        assertThat(result.loginSessionId()).isEqualTo("session-1");
        server.verify();
    }

    @Test
    void mapsReadTimeoutToGarminTimeout() {
        server.expect(requestTo("http://127.0.0.1:18090/internal/garmin/connect"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> client.connect("rider@example.com", "secret", "CN"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.GARMIN_CONNECT_TIMEOUT);
        server.verify();
    }

    @Test
    void mapsConnectionFailureToCollectorUnavailable() {
        server.expect(requestTo("http://127.0.0.1:18090/internal/garmin/connect"))
                .andRespond(withException(new ConnectException("connection refused")));

        assertThatThrownBy(() -> client.connect("rider@example.com", "secret", "CN"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.GARMIN_COLLECTOR_UNAVAILABLE);
        server.verify();
    }
}
