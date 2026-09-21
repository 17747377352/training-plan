-- 三项能力补齐所需的表与列。
--
-- 1) 训练状态：Garmin 服务端已按完整历史算好 ACWR、急性/慢性负荷与负荷平衡诊断，
--    这些是「该加量还是减量」最直接的依据，此前一个都没取。按日快照存储。
-- 2) 骑行 FTP 历史：FTP 既用于强度处方，也是功体比的分母。
-- 3) 活动心率区间：此前只有功率区间，无法交叉验证强度归属。
-- 4) 手工打卡：体重与主观感受（RPE）在 Garmin API 里根本不存在，只能用户自己填。

CREATE TABLE training_status (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    garmin_account_id BIGINT UNSIGNED NOT NULL COMMENT 'Garmin账号ID',
    calendar_date DATE NOT NULL COMMENT '数据归属日期',
    training_status INT NULL COMMENT 'Garmin 训练状态枚举值',
    training_status_phrase VARCHAR(64) NULL COMMENT '训练状态短语，如 PRODUCTIVE_6',
    acwr_percent INT NULL COMMENT '急性慢性负荷比（百分比）',
    acwr_status VARCHAR(32) NULL COMMENT 'ACWR 区间状态，如 OPTIMAL',
    acwr_ratio DOUBLE NULL COMMENT '急性/慢性负荷比值',
    acute_load INT NULL COMMENT '7 天急性负荷',
    chronic_load INT NULL COMMENT '28 天慢性负荷',
    chronic_load_min DOUBLE NULL COMMENT '慢性负荷合理区间下界',
    chronic_load_max DOUBLE NULL COMMENT '慢性负荷合理区间上界',
    load_aerobic_low DOUBLE NULL COMMENT '低强度有氧负荷',
    load_aerobic_low_target_min INT NULL COMMENT '低强度有氧目标下界',
    load_aerobic_low_target_max INT NULL COMMENT '低强度有氧目标上界',
    load_aerobic_high DOUBLE NULL COMMENT '高强度有氧负荷',
    load_aerobic_high_target_min INT NULL COMMENT '高强度有氧目标下界',
    load_aerobic_high_target_max INT NULL COMMENT '高强度有氧目标上界',
    load_anaerobic DOUBLE NULL COMMENT '无氧负荷',
    load_anaerobic_target_min INT NULL COMMENT '无氧目标下界',
    load_anaerobic_target_max INT NULL COMMENT '无氧目标上界',
    balance_feedback_phrase VARCHAR(64) NULL COMMENT '负荷平衡诊断，如 AEROBIC_LOW_SHORTAGE',
    vo2max_value DOUBLE NULL COMMENT '最新 VO2max',
    fitness_age INT NULL COMMENT 'Garmin 给出的体能年龄',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uniq_garmin_account_id_calendar_date (garmin_account_id, calendar_date),
    CONSTRAINT fk_training_status_account FOREIGN KEY (garmin_account_id) REFERENCES garmin_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每日训练状态与负荷';

CREATE TABLE ftp_history (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    garmin_account_id BIGINT UNSIGNED NOT NULL COMMENT 'Garmin账号ID',
    effective_date DATE NOT NULL COMMENT '该 FTP 的生效日期',
    ftp_watts INT NOT NULL COMMENT '功能阈值功率（瓦）',
    source VARCHAR(32) NOT NULL DEFAULT 'GARMIN' COMMENT '来源：GARMIN 自动测算 / MANUAL 手工录入',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uniq_garmin_account_id_effective_date (garmin_account_id, effective_date),
    CONSTRAINT fk_ftp_history_account FOREIGN KEY (garmin_account_id) REFERENCES garmin_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='骑行 FTP 历史';

-- 心率区间分布来自单独的活动接口，且区间个数随账号设置变化，
-- 因此单独建表而不是像功率区间那样在 activity 上加 5 个列。
CREATE TABLE activity_hr_zone (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    activity_id BIGINT UNSIGNED NOT NULL COMMENT '活动主键',
    zone_number INT NOT NULL COMMENT '区间序号，从 1 开始',
    zone_low_boundary INT NULL COMMENT '该区间心率下界（bpm）',
    seconds_in_zone INT NOT NULL DEFAULT 0 COMMENT '该区间停留秒数',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uniq_activity_id_zone_number (activity_id, zone_number),
    CONSTRAINT fk_activity_hr_zone_activity FOREIGN KEY (activity_id) REFERENCES activity (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='活动心率区间分布';

-- 手工打卡按平台用户存储而不是按 Garmin 账号：这些数据是用户自己填的，
-- 没有 Garmin 账号时同样应该能记；将来接入多个账号也不会重复。
CREATE TABLE daily_checkin (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '平台用户ID',
    calendar_date DATE NOT NULL COMMENT '数据归属日期',
    weight_kg DECIMAL(5,2) NULL COMMENT '体重（千克），Garmin 侧没有数据时由用户填写',
    rpe TINYINT UNSIGNED NULL COMMENT '主观疲劳度 1-10',
    note VARCHAR(512) NULL COMMENT '备注',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uniq_user_id_calendar_date (user_id, calendar_date),
    CONSTRAINT fk_daily_checkin_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每日手工打卡（体重、RPE、备注）';
