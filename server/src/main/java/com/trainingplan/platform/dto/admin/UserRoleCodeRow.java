package com.trainingplan.platform.dto.admin;

/**
 * 用户与角色编码的关联行，用于批量装配用户列表的角色。
 *
 * @param userId   用户 ID
 * @param roleCode 角色编码
 * @author gongxuesong
 * @date 2026-09-20
 */
public record UserRoleCodeRow(Long userId, String roleCode) {
}
