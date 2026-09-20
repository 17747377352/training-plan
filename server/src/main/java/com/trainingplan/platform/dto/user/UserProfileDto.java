package com.trainingplan.platform.dto.user;

import java.util.List;

/**
 * 当前用户资料。
 *
 * @param id       用户 ID
 * @param username 用户名
 * @param email    邮箱
 * @param roles    角色编码
 * @author gongxuesong
 * @date 2026-09-20
 */
public record UserProfileDto(Long id, String username, String email, List<String> roles) {
}

