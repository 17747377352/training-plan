package com.trainingplan.platform.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 公共配置。
 *
 * <p>Mapper 扫描放在本配置类而非启动类上：启动类始终参与 Web 层切片测试的上下文，
 * 若扫描挂在启动类上，切片测试会因缺少 SqlSessionFactory 而无法启动。</p>
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Configuration
@MapperScan("com.trainingplan.platform.mapper")
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(500L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}

