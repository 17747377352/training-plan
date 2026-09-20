package com.trainingplan.platform.service;

import com.trainingplan.platform.dto.SystemStatusDto;
import com.trainingplan.platform.service.impl.SystemServiceImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemServiceTest {

    private final SystemService systemService = new SystemServiceImpl();

    @Test
    void shouldReturnUpStatus() {
        SystemStatusDto status = systemService.getStatus();

        assertThat(status.application()).isEqualTo("training-plan-server");
        assertThat(status.status()).isEqualTo("UP");
        assertThat(status.timestamp()).isNotNull();
    }
}

