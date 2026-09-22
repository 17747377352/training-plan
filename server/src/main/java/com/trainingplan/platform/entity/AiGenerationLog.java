package com.trainingplan.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI 生成用量日志实体（追加型）。
 *
 * <p>配额与花费按本表统计，不能用 {@code training_plan} 计数——后者同一天重新
 * 生成会覆盖，数不出调用次数。</p>
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Data
@TableName("ai_generation_log")
public class AiGenerationLog {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 平台用户 ID。 */
    private Long userId;

    /** 归属日期（按上海时区）。 */
    private LocalDate calendarDate;

    /** 请求时间。 */
    private LocalDateTime requestedAt;

    /** 提示词版本。 */
    private String promptVersion;

    /** 模型名。 */
    private String model;

    /** 结果：SUCCESS/FAILED/REUSED。 */
    private String outcome;

    /** 输入 Token 数。 */
    private Integer promptTokens;

    /** 输出 Token 数。 */
    private Integer completionTokens;

    /** 合计 Token 数。 */
    private Integer totalTokens;

    /** 调用耗时（毫秒）。 */
    private Long elapsedMs;

    /** HTTP 状态码。 */
    private Integer httpStatus;

    /** 失败时的错误码。 */
    private String errorCode;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
