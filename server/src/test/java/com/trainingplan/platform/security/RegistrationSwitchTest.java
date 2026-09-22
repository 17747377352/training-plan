package com.trainingplan.platform.security;

import com.trainingplan.platform.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自助注册必须默认关闭。
 *
 * <p>这是上线安全的关键性质，但普通单测观察不到：{@code AuthServiceTest} 用反射
 * 显式设置开关，绕过了默认值，所以把 {@code @Value} 的默认值从 false 改成 true
 * 不会让任何用例失败（变异测试发现的缺口）。这里按 {@code EntitySchemaConsistencyTest}
 * 的做法做静态检查：既看注解默认值，也看随包发布的 application.yml。</p>
 *
 * @author gongxuesong
 * @date 2026-09-22
 */
class RegistrationSwitchTest {

    @Test
    void valueAnnotationDefaultsToClosed() throws Exception {
        Field field = AuthServiceImpl.class.getDeclaredField("registrationEnabled");
        Value annotation = field.getAnnotation(Value.class);

        assertThat(annotation).as("registrationEnabled 缺少 @Value").isNotNull();
        assertThat(annotation.value())
                .as("自助注册必须默认关闭：开放注册意味着任何人都能创建账号并调用付费的 AI 接口")
                .endsWith(":false}");
    }

    @Test
    void shippedConfigKeepsRegistrationClosed() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(yml)
                .as("application.yml 必须以环境变量覆盖、默认 false 的方式声明注册开关")
                .contains("registration-enabled: ${REGISTRATION_ENABLED:false}");
    }

    @Test
    void devExampleDocumentsTheLocalOverride() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application-dev.example.yml"));

        // 本地开发与 e2e 脚本需要注册账号，示例配置里显式打开；生产不要照抄这一行
        assertThat(yml).contains("registration-enabled: true");
    }
}
