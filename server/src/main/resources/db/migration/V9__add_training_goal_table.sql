-- 训练目标：用户当前想要什么。
--
-- 此前系统里只有「实际发生了什么」，没有任何地方能表达「想要什么」——而这是 AI
-- 给出**计划**而非**建议**所缺的最后一类信息，且只有用户能提供。
-- Garmin API 里不存在目标赛事、每周可练次数这类字段，所以只能手填。
--
-- 按平台用户存储（与 daily_checkin 一致）：这是人的目标，不是某个 Garmin 账号的。
-- 一个用户只保留一份当前目标（唯一键 user_id）：目标变更时覆盖，避免历史目标
-- 混进判灯与计划生成的上下文。
--
-- 注意：目标只影响「练什么」，不影响「能不能练」。判灯仍只看恢复信号，
-- 否则目标会变成绕过安全规则的入口。
CREATE TABLE training_goal (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '平台用户ID',
    goal_type VARCHAR(32) NOT NULL COMMENT '目标类型：POWER提升功率/MUSCLE增肌/ENDURANCE提升耐力/GENERAL保持状态/OTHER其他',
    target_date DATE NULL COMMENT '目标日期，如赛事或阶段节点',
    weekly_sessions INT NULL COMMENT '每周可训练次数',
    weekly_minutes INT NULL COMMENT '每周可投入总时长（分钟）',
    description VARCHAR(1000) NULL COMMENT '用户手动补充的描述：赛事名称、时间限制、偏好与禁忌等',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uniq_user_id (user_id),
    CONSTRAINT fk_training_goal_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户训练目标';
