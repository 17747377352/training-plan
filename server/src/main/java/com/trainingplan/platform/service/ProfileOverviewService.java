package com.trainingplan.platform.service;

import com.trainingplan.platform.dto.profile.ProfileOverviewDto;

/**
 * 个人中心身体数据。
 *
 * @author gongxuesong
 * @date 2026-09-24
 */
public interface ProfileOverviewService {

    /**
     * 取当前用户的身体数据聚合视图。
     *
     * @param userId 当前登录用户 ID
     * @return 各项最新值；账号下没有数据的项为 null
     */
    ProfileOverviewDto overview(Long userId);
}
