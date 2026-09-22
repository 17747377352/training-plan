package com.trainingplan.platform.dto.garmin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 桌面助手凭配对码回传令牌的请求。
 *
 * <p>这个接口不带用户令牌：令牌在用户自己机器上换取，助手并不知道平台登录态，
 * 因此改用一次性配对码定位平台用户。</p>
 *
 * @param code      平台页面上领取的一次性配对码
 * @param email     Garmin 登录邮箱
 * @param tokenJson Garmin 令牌 JSON
 * @param region    站点，只能为 GLOBAL 或 CN
 * @author gongxuesong
 * @date 2026-09-22
 */
public record PairBindRequest(
        @NotBlank @Size(max = 32) String code,
        @NotBlank @Email @Size(max = 128) String email,
        @NotBlank @Size(max = 8192) String tokenJson,
        @NotBlank @Pattern(regexp = "^(GLOBAL|CN)$", message = "区域只能为 GLOBAL 或 CN") String region) {
}
