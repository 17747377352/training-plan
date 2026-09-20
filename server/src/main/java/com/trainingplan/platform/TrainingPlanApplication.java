package com.trainingplan.platform;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Training Plan 服务端启动入口。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@SpringBootApplication
@MapperScan("com.trainingplan.platform.mapper")
public class TrainingPlanApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrainingPlanApplication.class, args);
    }
}
