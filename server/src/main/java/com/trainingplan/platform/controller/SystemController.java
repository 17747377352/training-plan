package com.trainingplan.platform.controller;

import com.trainingplan.platform.common.api.Result;
import com.trainingplan.platform.dto.SystemStatusDto;
import com.trainingplan.platform.service.SystemService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统状态接口。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final SystemService systemService;

    @GetMapping("/health")
    public Result<SystemStatusDto> health() {
        return Result.success(systemService.getStatus());
    }
}

