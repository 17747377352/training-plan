-- AI 生成用量日志（追加型）。
--
-- 为什么不能靠 training_plan 计数：那张表是「当前计划」，唯一键 (user_id,
-- calendar_date) 决定了同一天重新生成会**覆盖**，因此无法统计当天调用了几次。
-- 配额与花费必须由追加型日志来数。
--
-- 记录阶段七要求的「模型、耗时、HTTP 状态和 Token 用量」，并区分三种结果：
--   SUCCESS 真实调用模型且结果通过校验
--   FAILED  真实调用了模型但失败（校验不通过、超时、限流等）——仍然消耗配额，
--           因为请求可能已经产生费用，按「实际调用次数」计费才诚实
--   REUSED  数据指纹未变，直接返回已存计划，**没有调用模型、不计入配额**
CREATE TABLE ai_generation_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '平台用户ID',
    calendar_date DATE NOT NULL COMMENT '归属日期（按上海时区）',
    requested_at DATETIME(3) NOT NULL COMMENT '请求时间',
    prompt_version VARCHAR(32) NULL COMMENT '提示词版本',
    model VARCHAR(64) NULL COMMENT '模型名',
    outcome VARCHAR(16) NOT NULL COMMENT '结果：SUCCESS/FAILED/REUSED',
    prompt_tokens INT NULL COMMENT '输入 Token 数',
    completion_tokens INT NULL COMMENT '输出 Token 数',
    total_tokens INT NULL COMMENT '合计 Token 数',
    elapsed_ms BIGINT NULL COMMENT '调用耗时（毫秒）',
    http_status INT NULL COMMENT 'HTTP 状态码，未发起请求时为空',
    error_code VARCHAR(64) NULL COMMENT '失败时的错误码，不记录模型原文',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_user_id_calendar_date (user_id, calendar_date),
    CONSTRAINT fk_ai_generation_log_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 生成用量日志';
