-- 持久化 AI 生成的训练计划。
--
-- 此前生成结果只存在于接口响应里：刷新即失、无法回看、也无法判断「计划 vs 实际」。
-- 同时 generatedAt / dataFingerprint / promptVersion 这些字段本来就是为了记录而留的，
-- 开发计划阶段七也要求「记录模型、耗时、HTTP 状态和 Token 用量」。
--
-- 一个用户一天只保留一份计划（唯一键 user_id + calendar_date）：重新生成即覆盖，
-- 刷新时读回当天那一份。若要保留每次生成的完整历史，应另建追加型表而不是放宽唯一键。
CREATE TABLE training_plan (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '平台用户ID',
    garmin_account_id BIGINT UNSIGNED NULL COMMENT '生成时使用的 Garmin 账号，未绑定时为空',
    calendar_date DATE NOT NULL COMMENT '计划归属日期',
    light VARCHAR(16) NOT NULL COMMENT '生成时的恢复灯色',
    plan_type VARCHAR(16) NOT NULL COMMENT '计划类型：REST/RECOVERY/ENDURANCE',
    title VARCHAR(120) NOT NULL COMMENT '计划名称',
    duration_minutes INT NOT NULL DEFAULT 0 COMMENT '总时长（分钟）',
    intensity VARCHAR(200) NULL COMMENT '强度说明',
    rationale VARCHAR(1600) NULL COMMENT '依据用户实际指标的分析',
    adjustment VARCHAR(600) NULL COMMENT '热身后如何按体感调整',
    steps_json TEXT NULL COMMENT '处方分段 JSON 数组，含名称/分钟/FTP百分比/瓦数/体感',
    provider VARCHAR(32) NULL COMMENT '模型提供方',
    model VARCHAR(64) NULL COMMENT '模型名',
    prompt_version VARCHAR(32) NULL COMMENT '提示词版本',
    data_fingerprint CHAR(32) NULL COMMENT '生成时上下文摘要，用于判断数据是否已变化',
    data_summary VARCHAR(512) NULL COMMENT '生成时使用的数据量摘要',
    generated_at DATETIME(3) NULL COMMENT '模型生成时间',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uniq_user_id_calendar_date (user_id, calendar_date),
    CONSTRAINT fk_training_plan_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_training_plan_account FOREIGN KEY (garmin_account_id) REFERENCES garmin_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 生成的每日训练计划';
