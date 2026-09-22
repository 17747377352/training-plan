package com.trainingplan.platform.client;

import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.config.CollectorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 基于 HTTP 的 Collector 认证客户端实现。
 *
 * <p>Collector 不对外暴露，仅允许内网访问，调用时携带内部服务凭据。
 * 只要 Collector 不可达就返回明确的业务错误，不把底层异常暴露给调用方。</p>
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Slf4j
@Component
@EnableConfigurationProperties(CollectorProperties.class)
public class RestGarminAuthClient implements GarminAuthClient {

    private static final String SERVICE_TOKEN_HEADER = "X-Collector-Token";

    private final RestClient restClient;

    public RestGarminAuthClient(RestClient.Builder builder, CollectorProperties properties) {
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader(SERVICE_TOKEN_HEADER, properties.serviceToken())
                .requestFactory(requestFactory(properties.timeout()))
                .build();
    }

    @Override
    public CollectorAuthResult connect(String email, String password, String region) {
        return post("/internal/garmin/connect",
                Map.of("email", email, "password", password, "region", region));
    }

    @Override
    public CollectorAuthResult submitMfa(String loginSessionId, String mfaCode) {
        return post("/internal/garmin/connect/mfa",
                Map.of("loginSessionId", loginSessionId, "mfaCode", mfaCode));
    }

    @Override
    public CollectorAuthResult verifyToken(String tokenJson, String region) {
        return post("/internal/garmin/verify-token",
                Map.of("tokenJson", tokenJson, "region", region));
    }

    private CollectorAuthResult post(String path, Map<String, String> payload) {
        String callId = UUID.randomUUID().toString().substring(0, 8);
        long startedNanos = System.nanoTime();
        log.info("调用 Collector 认证接口开始 callId={} path={}", callId, path);
        try {
            CollectorAuthResponse response = restClient.post()
                    .uri(path)
                    .body(payload)
                    .retrieve()
                    .body(CollectorAuthResponse.class);
            if (response == null) {
                throw new BusinessException(ErrorCode.GARMIN_COLLECTOR_UNAVAILABLE);
            }
            log.info("调用 Collector 认证接口完成 callId={} path={} elapsedMs={} status={}",
                    callId, path, elapsedMillis(startedNanos), response.status());
            return new CollectorAuthResult(
                    response.status(), response.tokenJson(), response.loginSessionId(), response.message());
        } catch (ResourceAccessException exception) {
            Throwable cause = exception.getMostSpecificCause();
            boolean timedOut = cause instanceof SocketTimeoutException;
            log.error("调用 Collector 认证接口失败 callId={} path={} elapsedMs={} failureType={} causeType={}",
                    callId, path, elapsedMillis(startedNanos),
                    timedOut ? "TIMEOUT" : "CONNECTION",
                    cause.getClass().getSimpleName());
            // 只有真实的 SocketTimeoutException 才应提示登录耗时过长；连接拒绝、DNS
            // 等错误应明确归为采集器不可用，避免几秒内失败却显示成 120 秒超时。
            throw new BusinessException(timedOut
                    ? ErrorCode.GARMIN_CONNECT_TIMEOUT
                    : ErrorCode.GARMIN_COLLECTOR_UNAVAILABLE);
        } catch (RestClientException exception) {
            log.error("调用 Collector 认证接口失败 callId={} path={} elapsedMs={} failureType={}",
                    callId, path, elapsedMillis(startedNanos), exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.GARMIN_COLLECTOR_UNAVAILABLE);
        }
    }

    private long elapsedMillis(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private SimpleClientHttpRequestFactory requestFactory(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return factory;
    }

    /**
     * Collector 认证接口的原始响应体。
     *
     * @param status         CONNECTED 或 MFA_REQUIRED
     * @param tokenJson      Garmin Token JSON，仅 CONNECTED 时返回
     * @param loginSessionId MFA 会话 ID，仅 MFA_REQUIRED 时返回
     * @param message        失败提示
     */
    private record CollectorAuthResponse(String status, String tokenJson, String loginSessionId, String message) {
    }
}
