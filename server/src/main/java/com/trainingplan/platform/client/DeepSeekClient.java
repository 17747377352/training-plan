package com.trainingplan.platform.client;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * DeepSeek 调用客户端。
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
public interface DeepSeekClient {

    /**
     * 调用模型生成计划。
     *
     * @param systemPrompt 系统提示词
     * @param context      字段白名单上下文
     * @return 结果与用量
     */
    DeepSeekResult generate(String systemPrompt, JsonNode context);
}
