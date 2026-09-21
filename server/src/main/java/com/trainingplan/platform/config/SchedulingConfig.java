package com.trainingplan.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务配置。
 *
 * <p>独立成配置类而非放在启动类上：切片测试不会加载普通 {@code @Configuration}，
 * 避免测试被定时任务干扰。</p>
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
