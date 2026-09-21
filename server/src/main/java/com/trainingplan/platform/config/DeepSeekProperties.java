package com.trainingplan.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

/** 服务端 DeepSeek 配置；API Key 不进入接口响应或前端构建。 */
@ConfigurationProperties(prefix = "app.deepseek")
public record DeepSeekProperties(
        @DefaultValue("https://api.deepseek.com") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("deepseek-flash") String model,
        @DefaultValue("90s") Duration timeout) {}
