package com.trainingplan.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Training Plan 服务端启动入口。
 *
 * <p>Mapper 扫描配置位于 {@code com.trainingplan.platform.config.MybatisPlusConfig}，
 * 不放在启动类上，避免 Web 层切片测试被迫初始化 SqlSessionFactory。</p>
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@SpringBootApplication
public class TrainingPlanApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrainingPlanApplication.class, args);
    }
}
