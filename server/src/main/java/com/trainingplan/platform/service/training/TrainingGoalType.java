package com.trainingplan.platform.service.training;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 训练目标类型。
 *
 * <p>代码值是存储与接口用的稳定标识，中文名只用于展示。请求校验用的正则与此处
 * 必须一致，由 {@code TrainingGoalServiceTest} 断言，避免两处各自漂移。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Getter
@RequiredArgsConstructor
public enum TrainingGoalType {

    /** 提升功率。 */
    POWER("提升功率"),
    /** 增肌。 */
    MUSCLE("增肌"),
    /** 提升耐力。 */
    ENDURANCE("提升耐力"),
    /** 保持状态。 */
    GENERAL("保持状态"),
    /** 其他。 */
    OTHER("其他");

    private final String label;

    private static final Map<String, TrainingGoalType> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toMap(Enum::name, Function.identity()));

    /**
     * 按代码查类型。
     *
     * @param code 代码
     * @return 类型，无法识别时返回 null
     */
    public static TrainingGoalType fromCode(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * 按代码取中文名。
     *
     * @param code 代码
     * @return 中文名，无法识别时原样返回代码
     */
    public static String labelOf(String code) {
        TrainingGoalType type = fromCode(code);
        return type == null ? code : type.getLabel();
    }
}
