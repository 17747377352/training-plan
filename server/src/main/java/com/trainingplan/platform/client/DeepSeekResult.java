package com.trainingplan.platform.client;

/**
 * 一次 DeepSeek 调用的结果与用量。
 *
 * <p>Token 用量与耗时按阶段七要求记录，用于配额与花费统计；失败时不返回本对象，
 * 由调用方在异常里拿到错误码后自行记账。</p>
 *
 * @param content          模型返回的正文
 * @param promptTokens     输入 Token 数
 * @param completionTokens 输出 Token 数
 * @param totalTokens      合计 Token 数
 * @param elapsedMillis    调用耗时（毫秒）
 * @param httpStatus       HTTP 状态码
 * @author gongxuesong
 * @date 2026-09-21
 */
public record DeepSeekResult(String content,
                             Integer promptTokens,
                             Integer completionTokens,
                             Integer totalTokens,
                             long elapsedMillis,
                             int httpStatus) {
}
