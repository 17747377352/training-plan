package com.trainingplan.platform.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.config.DeepSeekProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** DeepSeek Chat Completions + JSON 输出。失败明确返回，不把规则处方冒充 AI 结果。 */
@Component
@EnableConfigurationProperties(DeepSeekProperties.class)
public class RestDeepSeekClient implements DeepSeekClient {
    private final RestClient client;
    private final DeepSeekProperties properties;

    public RestDeepSeekClient(RestClient.Builder builder, DeepSeekProperties properties) {
        this.properties = properties;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(properties.timeout());
        client = builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }

    @Override
    public DeepSeekResult generate(String systemPrompt, JsonNode context) {
        if (!StringUtils.hasText(properties.apiKey())) {
            throw new BusinessException(ErrorCode.AI_NOT_CONFIGURED);
        }
        long startedAt = System.nanoTime();
        try {
            ResponseEntity<JsonNode> entity = client.post().uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                    .body(Map.of("model", properties.model(), "stream", false,
                            "max_tokens", 2400, "temperature", 0.3,
                            "thinking", Map.of("type", "disabled"),
                            "response_format", Map.of("type", "json_object"),
                            "messages", List.of(Map.of("role", "system", "content", systemPrompt),
                                    Map.of("role", "user", "content", context.toString()))))
                    .retrieve().toEntity(JsonNode.class);
            JsonNode response = entity.getBody();
            JsonNode choice = response == null ? null : response.path("choices").path(0);
            if (choice == null || !"stop".equals(choice.path("finish_reason").asText())) {
                throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
            }
            String content = choice.path("message").path("content").asText();
            if (!StringUtils.hasText(content) || content.length() > 24000) {
                throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
            }
            // Token 用量按阶段七要求记录；供应商缺字段时留空而不是记 0
            JsonNode usage = response.path("usage");
            return new DeepSeekResult(content,
                    intOrNull(usage, "prompt_tokens"),
                    intOrNull(usage, "completion_tokens"),
                    intOrNull(usage, "total_tokens"),
                    (System.nanoTime() - startedAt) / 1_000_000,
                    entity.getStatusCode().value());
        } catch (ResourceAccessException exception) {
            throw new BusinessException(ErrorCode.AI_TIMEOUT);
        } catch (RestClientResponseException exception) {
            // 不透传供应商响应体，避免把密钥、提示词或用户训练数据带入错误页面/日志。
            int status = exception.getStatusCode().value();
            if (status == 401 || status == 403) throw new BusinessException(ErrorCode.AI_AUTH_ERROR);
            if (status == 429) throw new BusinessException(ErrorCode.AI_RATE_LIMITED);
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR);
        } catch (RestClientException exception) {
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR);
        }
    }

    /**
     * 取整数用量字段。
     *
     * @param usage 用量节点
     * @param field 字段名
     * @return 数值，缺失时为 null
     */
    private Integer intOrNull(JsonNode usage, String field) {
        JsonNode value = usage.path(field);
        return value.isIntegralNumber() ? value.intValue() : null;
    }
}
