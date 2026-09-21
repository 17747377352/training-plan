package com.trainingplan.platform.client;

import com.fasterxml.jackson.databind.JsonNode;

/** 用于隔离模型 HTTP 协议与用户数据汇总逻辑，也便于验证真实请求体。 */
public interface DeepSeekClient {
    String generate(String systemPrompt, JsonNode context);
}
