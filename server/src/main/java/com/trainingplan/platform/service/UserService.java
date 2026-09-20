package com.trainingplan.platform.service;

import com.trainingplan.platform.dto.user.UserProfileDto;

/**
 * 平台用户查询服务。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
public interface UserService {

    /**
     * 查询可用用户资料及角色。
     *
     * @param userId 用户 ID
     * @return 用户资料
     */
    UserProfileDto getProfile(Long userId);
}

