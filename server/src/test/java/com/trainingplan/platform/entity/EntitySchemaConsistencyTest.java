package com.trainingplan.platform.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体字段与迁移脚本的静态一致性校验。
 *
 * <p>MyBatis-Plus 默认把驼峰字段名转成下划线列名，规则是「仅在大写字母前插入下划线，
 * 数字前后不插」。因此 {@code max20minPower} 推导为 {@code max20min_power}，
 * 建表时若写成 {@code max_20min_power}，运行时才会抛 {@code Unknown column}，
 * 普通单元测试发现不了。</p>
 *
 * <p>本测试不连数据库，直接对比实体与迁移脚本，用来提前拦住这类错配。</p>
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
class EntitySchemaConsistencyTest {

    private static final List<Class<?>> ENTITIES = List.of(
            SysUser.class, SysRole.class, SysUserRole.class, GarminAccount.class,
            SyncJob.class, DailyHealth.class, SleepRecord.class, HrvRecord.class, Activity.class,
            TrainingStatus.class, FtpHistory.class, ActivityHrZone.class, DailyCheckin.class);

    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE TABLE (\\w+)\\s*\\((.*?)\\n\\)\\s*ENGINE", Pattern.DOTALL);
    /**
     * ALTER TABLE 语句本体（到分号为止）。
     *
     * <p>不能直接写 {@code ALTER TABLE (\w+)\s+ADD COLUMN (\w+)}：一条 ALTER 里
     * 可以并列多个 ADD COLUMN，那样只会匹配到第一个，后面的列会被漏掉，
     * 实体改了列名也照样通过。</p>
     */
    private static final Pattern ALTER_TABLE =
            Pattern.compile("ALTER TABLE (\\w+)([^;]*);", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ADD_COLUMN =
            Pattern.compile("ADD COLUMN (\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern COLUMN_LINE = Pattern.compile("^(\\w+)\\s+[A-Za-z]");
    private static final Set<String> NON_COLUMN_PREFIXES =
            Set.of("PRIMARY", "UNIQUE", "KEY", "CONSTRAINT", "INDEX", "FOREIGN");

    @Test
    void everyEntityFieldMapsToAnExistingColumn() throws Exception {
        Map<String, Set<String>> schema = loadSchema();

        List<String> problems = new ArrayList<>();
        for (Class<?> entity : ENTITIES) {
            TableName tableName = entity.getAnnotation(TableName.class);
            assertThat(tableName).as("%s 缺少 @TableName", entity.getSimpleName()).isNotNull();
            Set<String> columns = schema.getOrDefault(tableName.value(), Set.of());
            assertThat(columns).as("迁移脚本里找不到表 %s", tableName.value()).isNotEmpty();

            for (Field field : entity.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                TableField tableField = field.getAnnotation(TableField.class);
                String column = tableField != null && !tableField.value().isBlank()
                        ? tableField.value()
                        : camelToUnderline(field.getName());
                if (!columns.contains(column)) {
                    problems.add("%s.%s 推导为列 %s，但表 %s 没有该列".formatted(
                            entity.getSimpleName(), field.getName(), column, tableName.value()));
                }
            }
        }

        assertThat(problems).as("实体与迁移脚本存在字段错配").isEmpty();
    }

    /**
     * 复刻 MyBatis-Plus 的驼峰转下划线规则。
     *
     * @param name 字段名
     * @return 推导出的列名
     */
    private String camelToUnderline(String name) {
        StringBuilder builder = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isUpperCase(c)) {
                builder.append('_');
            }
            builder.append(Character.toLowerCase(c));
        }
        return builder.toString();
    }

    /**
     * 读取迁移脚本中的表与列，包含 ALTER TABLE 追加的列。
     *
     * @return 表名到列名集合
     */
    private Map<String, Set<String>> loadSchema() throws Exception {
        Path migrationDir = Path.of("src/main/resources/db/migration");
        Map<String, Set<String>> schema = new HashMap<>();
        try (Stream<Path> files = Files.list(migrationDir)) {
            List<Path> sqlFiles = files
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted()
                    .toList();
            for (Path file : sqlFiles) {
                // 先去掉行注释，避免注释里提到的列名被当成真实列
                String sql = LINE_COMMENT.matcher(Files.readString(file)).replaceAll("");
                Matcher create = CREATE_TABLE.matcher(sql);
                while (create.find()) {
                    Set<String> columns = schema.computeIfAbsent(create.group(1), key -> new HashSet<>());
                    for (String line : create.group(2).split("\n")) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()
                                || NON_COLUMN_PREFIXES.stream().anyMatch(trimmed::startsWith)) {
                            continue;
                        }
                        Matcher column = COLUMN_LINE.matcher(trimmed);
                        if (column.find()) {
                            columns.add(column.group(1));
                        }
                    }
                }
                Matcher alter = ALTER_TABLE.matcher(sql);
                while (alter.find()) {
                    Set<String> columns =
                            schema.computeIfAbsent(alter.group(1), key -> new HashSet<>());
                    Matcher added = ADD_COLUMN.matcher(alter.group(2));
                    while (added.find()) {
                        columns.add(added.group(1));
                    }
                }
            }
        }
        return schema;
    }
}
