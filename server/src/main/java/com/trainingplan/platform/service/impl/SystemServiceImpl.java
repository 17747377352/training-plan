package com.trainingplan.platform.service.impl;

import com.trainingplan.platform.dto.SystemStatusDto;
import com.trainingplan.platform.service.SystemService;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 系统状态服务实现。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Service
public class SystemServiceImpl implements SystemService {

    @Override
    public SystemStatusDto getStatus() {
        return new SystemStatusDto("training-plan-server", "UP", Instant.now());
    }
}

