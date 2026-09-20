package com.trainingplan.platform.dto.admin;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理员视角的平台用户信息。
 *
 * @param id         用户 ID
 * @param username   用户名
 * @param email      邮箱
 * @param status     状态：0 禁用，1 启用
 * @param roles      角色编码
 * @param createTime 注册时间
 * @author gongxuesong
 * @date 2026-09-20
 */
public record AdminUserDto(Long id,
                           String username,
                           String email,
                           Integer status,
                           List<String> roles,
                           LocalDateTime createTime) {
}
