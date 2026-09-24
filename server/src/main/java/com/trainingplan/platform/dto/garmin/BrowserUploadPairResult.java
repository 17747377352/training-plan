package com.trainingplan.platform.dto.garmin;

/**
 * 上传配对结果。令牌仅在签发时返回一次，不得写入日志或版本库。
 *
 * @param accountId 凭据唯一绑定的 Garmin 账号
 * @param uploadToken 专用上传令牌，只授予该账号的数据上传权限
 * @param warning 配对成功但需要提醒的事（例如账号还没有负荷分布数据）；没有则为 null
 * @author gongxuesong
 * @date 2026-09-24
 */
public record BrowserUploadPairResult(Long accountId, String uploadToken, String warning) {
}
