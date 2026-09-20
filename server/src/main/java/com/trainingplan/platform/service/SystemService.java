package com.trainingplan.platform.service;

import com.trainingplan.platform.dto.SystemStatusDto;

/**
 * 系统状态服务。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
public interface SystemService {

    /**
     * 获取当前服务状态。
     *
     * @return 系统状态
     */
    SystemStatusDto getStatus();
}

