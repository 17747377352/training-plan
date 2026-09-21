package com.trainingplan.platform.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.config.DeepSeekProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 校验实际 Chat Completions 请求体、JSON 模式和供应商失败分流。 */
class RestDeepSeekClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private MockRestServiceServer server;
    private RestDeepSeekClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = org.mockito.Mockito.spy(RestClient.builder());
        server = MockRestServiceServer.bindTo(builder).build();
        // 保留测试服务器的请求工厂，禁止测试带假密钥访问真实 DeepSeek。
        org.mockito.Mockito.doReturn(builder).when(builder).requestFactory(org.mockito.ArgumentMatchers.any());
        client = new RestDeepSeekClient(builder, new DeepSeekProperties("https://api.deepseek.com", "unit-test-key", "deepseek-flash", Duration.ofSeconds(90)));
    }

    @Test
    void postsConfiguredModelAndUserMetricsWithoutLosingJsonMode() {
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andExpect(header("Authorization", "Bearer unit-test-key"))
                .andExpect(jsonPath("$.model").value("deepseek-flash"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].content").value("{\"currentFtpWatts\":213}"))
                .andRespond(withSuccess("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"title\\\":\\\"恢复骑\\\"}\"}}]}", MediaType.APPLICATION_JSON));
        assertThat(client.generate("返回 JSON", json.createObjectNode().put("currentFtpWatts", 213))).contains("恢复骑");
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"401,AI_AUTH_ERROR", "403,AI_AUTH_ERROR", "429,AI_RATE_LIMITED", "500,AI_SERVICE_ERROR"})
    void vendorErrorsAreSanitized(int status, String error) {
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("private training data and secret"));
        assertThatThrownBy(() -> client.generate("json", json.createObjectNode()))
                .isInstanceOf(BusinessException.class).hasMessageNotContaining("private")
                .extracting("errorCode").isEqualTo(ErrorCode.valueOf(error));
        server.verify();
    }

    @Test
    void missingKeyDoesNotMakeNetworkRequest() {
        RestDeepSeekClient unconfigured = new RestDeepSeekClient(RestClient.builder(),
                new DeepSeekProperties("https://api.deepseek.com", "", "deepseek-flash", Duration.ofSeconds(90)));
        assertThatThrownBy(() -> unconfigured.generate("json", json.createObjectNode()))
                .extracting("errorCode").isEqualTo(ErrorCode.AI_NOT_CONFIGURED);
    }

    @Test
    void timeoutHasDedicatedErrorAndNoAutomaticRetry() {
        server.expect(requestTo("https://api.deepseek.com/chat/completions"))
                .andRespond(withException(new SocketTimeoutException()));
        assertThatThrownBy(() -> client.generate("json", json.createObjectNode()))
                .extracting("errorCode").isEqualTo(ErrorCode.AI_TIMEOUT);
        server.verify();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{}", "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{}\"}}]}",
            "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"\"}}]}"})
    void emptyOrTruncatedResponseIsNotAPlan(String body) {
        server.expect(requestTo("https://api.deepseek.com/chat/completions")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.generate("json", json.createObjectNode()))
                .extracting("errorCode").isEqualTo(ErrorCode.AI_INVALID_RESPONSE);
    }
}
