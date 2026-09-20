package com.trainingplan.platform.service;

import com.trainingplan.platform.common.api.PageResult;
import com.trainingplan.platform.dto.admin.AdminUserDto;
import com.trainingplan.platform.dto.admin.AdminUserQuery;

/**
 * 管理员用户管理服务。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
public interface AdminUserService {

    /**
     * 分页查询平台用户。
     *
     * @param query 分页与过滤条件
     * @return 用户分页结果
     */
    PageResult<AdminUserDto> listUsers(AdminUserQuery query);

    /**
     * 启用或禁用平台用户。
     *
     * @param userId     目标用户 ID
     * @param status     目标状态：0 禁用，1 启用
     * @param operatorId 当前操作的管理员 ID
     */
    void updateUserStatus(Long userId, Integer status, Long operatorId);
}
