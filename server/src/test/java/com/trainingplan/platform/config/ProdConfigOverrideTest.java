package com.trainingplan.platform.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 生产 profile 配置不得用字面量覆盖 application.yml 里的环境变量占位符。
 *
 * <p>Spring Boot 的 profile 配置优先级高于基础配置，而两者的优先级都低于操作系统环境变量
 * —— 但那是针对"同名属性"而言的。{@code app.collector.base-url} 这条属性只能通过
 * application.yml 里的 {@code ${COLLECTOR_BASE_URL:...}} 占位符读到环境变量；一旦
 * application-prod.yml 又把它写成字面量，占位符解析结果就被整个覆盖掉，容器注入的
 * 环境变量静默失效。</p>
 *
 * <p>生产曾因此把 {@code COLLECTOR_BASE_URL=http://127.0.0.1:18090} 用成了写死的
 * {@code 8090}，表现为"Garmin 采集服务暂不可用"（46005），且错误日志里的 target 恰好是
 * 8090，容易误判为网络或采集器未启动（实测采集器在 18090 上健康，且用同一 token 请求
 * {@code /internal/garmin/verify-token} 返回 422 而非 401，证明地址与凭据都是好的）。</p>
 *
 * <p>普通单测观察不到这一点：测试不加载 prod profile，{@code CollectorProperties} 拿到的
 * 始终是测试上下文里的值。因此这里按 {@code RegistrationSwitchTest}、
 * {@code EntitySchemaConsistencyTest} 的静态检查做法，直接比对随包发布的配置文件。</p>
 *
 * @author gongxuesong
 * @date 2026-09-22
 */
class ProdConfigOverrideTest {

    private static final Path BASE_YML = Path.of("src/main/resources/application.yml");
    private static final Path PROD_YML = Path.of("src/main/resources/application-prod.yml");

    @Test
    void baseConfigReadsCollectorAddressFromEnvironment() throws IOException {
        assertThat(leaves(BASE_YML))
                .as("application.yml 必须以环境变量覆盖、并带一个本机默认值的方式声明采集器地址")
                .containsEntry("app.collector.base-url",
                        "${COLLECTOR_BASE_URL:http://127.0.0.1:8090}")
                .containsEntry("app.collector.service-token", "${COLLECTOR_SERVICE_TOKEN:}");
    }

    @Test
    void prodConfigDoesNotShadowEnvironmentPlaceholders() throws IOException {
        // application-prod.yml 是 gitignored 的本机文件，用于保存生产凭据；
        // 在没放这个文件的机器上（如 CI）跳过比对，但仍保证上面的基础配置检查生效。
        assumeTrue(Files.exists(PROD_YML), "本机没有 application-prod.yml，跳过 prod 覆盖检查");

        Set<String> envDriven = placeholdersIn(BASE_YML);
        Map<String, String> prodLeaves = leaves(PROD_YML);

        Set<String> shadowed = new TreeSet<>();
        envDriven.forEach(path -> {
            String value = prodLeaves.get(path);
            if (value != null && !value.contains("${")) {
                shadowed.add(path + " = " + value);
            }
        });

        assertThat(shadowed)
                .as("application-prod.yml 用字面量覆盖了这些本应由环境变量驱动的属性，"
                        + "容器注入的同名环境变量会被静默忽略。请改成 ${VAR:当前值} 形式，"
                        + "保留原值作为本机默认值（生产曾因此把采集器地址 18090 用成 8090）")
                .isEmpty();
    }

    /** application.yml 里声明了 ${ENV:默认值} 占位符的属性路径。 */
    private static Set<String> placeholdersIn(Path yml) throws IOException {
        Set<String> result = new TreeSet<>();
        leaves(yml).forEach((path, value) -> {
            if (value.contains("${")) {
                result.add(path);
            }
        });
        return result;
    }

    /**
     * 把扁平的 YAML 解析成 {@code 属性路径 -> 原始值}。
     *
     * <p>只处理本项目配置文件用到的形式：按空格缩进分层的 {@code key: value} 与
     * {@code key:}，忽略注释与空行。够用即可，不追求通用 YAML 语义。</p>
     */
    private static Map<String, String> leaves(Path yml) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        Deque<String> keys = new ArrayDeque<>();
        List<Integer> indents = new ArrayList<>();

        for (String rawLine : Files.readAllLines(yml)) {
            String line = stripComment(rawLine);
            if (line.isBlank()) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            String body = line.strip();
            int colon = body.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = body.substring(0, colon).strip();
            String value = body.substring(colon + 1).strip();

            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                keys.removeLast();
            }

            if (value.isEmpty()) {
                keys.addLast(key);
                indents.add(indent);
            } else {
                result.put(String.join(".", keys) + (keys.isEmpty() ? "" : ".") + key, value);
            }
        }
        return result;
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }
}
