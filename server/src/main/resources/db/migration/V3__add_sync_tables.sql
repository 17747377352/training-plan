-- 同步阶段首批数据表。
--
-- 命名与空值约定：
--   * 缺失指标一律存 NULL，不使用 0 代替（Garmin 不同设备/账号支持的指标不同）。
--   * 睡眠按「睡眠开始时间」而非日期去重：同一天可能存在午睡等多条记录，
--     用 calendar_date 做唯一键会把午睡合并掉。
--   * 时间统一存 GMT，展示时再按本地时区换算；中国区账号的
--     sleepStartTimestampLocal 存在被重复换算的报告（见 garminconnect 文档）。

CREATE TABLE daily_health (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    garmin_account_id BIGINT UNSIGNED NOT NULL COMMENT 'Garmin账号ID',
    calendar_date DATE NOT NULL COMMENT '数据归属日期',
    steps INT NULL COMMENT '步数',
    distance_meters DOUBLE NULL COMMENT '距离（米）',
    total_kilocalories DOUBLE NULL COMMENT '总热量（千卡）',
    active_kilocalories DOUBLE NULL COMMENT '活动热量（千卡）',
    resting_heart_rate INT NULL COMMENT '静息心率（bpm）',
    min_heart_rate INT NULL COMMENT '最低心率（bpm）',
    max_heart_rate INT NULL COMMENT '最高心率（bpm）',
    average_stress_level INT NULL COMMENT '平均压力',
    body_battery_highest INT NULL COMMENT '身体电量最高值',
    body_battery_lowest INT NULL COMMENT '身体电量最低值',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uniq_garmin_account_id_calendar_date (garmin_account_id, calendar_date),
    CONSTRAINT fk_daily_health_account FOREIGN KEY (garmin_account_id) REFERENCES garmin_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每日健康汇总';

CREATE TABLE sleep_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    garmin_account_id BIGINT UNSIGNED NOT NULL COMMENT 'Garmin账号ID',
    calendar_date DATE NOT NULL COMMENT '醒来日期',
    sleep_start_gmt DATETIME(3) NOT NULL COMMENT '入睡时间（GMT）',
    sleep_end_gmt DATETIME(3) NULL COMMENT '醒来时间（GMT）',
    sleep_time_seconds INT NULL COMMENT '总睡眠时长（秒）',
    deep_sleep_seconds INT NULL COMMENT '深睡时长（秒）',
    light_sleep_seconds INT NULL COMMENT '浅睡时长（秒）',
    rem_sleep_seconds INT NULL COMMENT 'REM 时长（秒）',
    awake_sleep_seconds INT NULL COMMENT '清醒时长（秒）',
    sleep_score INT NULL COMMENT '睡眠评分',
    avg_sleep_hrv DOUBLE NULL COMMENT '睡眠期间平均 HRV（毫秒）',
    avg_spo2 DOUBLE NULL COMMENT '睡眠期间平均血氧（%）',
    avg_respiration DOUBLE NULL COMMENT '睡眠期间平均呼吸（次/分）',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uniq_garmin_account_id_sleep_start_gmt (garmin_account_id, sleep_start_gmt),
    KEY idx_calendar_date (calendar_date),
    CONSTRAINT fk_sleep_record_account FOREIGN KEY (garmin_account_id) REFERENCES garmin_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='睡眠记录';

CREATE TABLE hrv_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    garmin_account_id BIGINT UNSIGNED NOT NULL COMMENT 'Garmin账号ID',
    calendar_date DATE NOT NULL COMMENT '数据归属日期',
    last_night_avg DOUBLE NULL COMMENT '夜间平均 HRV（毫秒）',
    weekly_avg DOUBLE NULL COMMENT '7 天平均 HRV（毫秒）',
    hrv_status VARCHAR(32) NULL COMMENT 'HRV 状态，如 BALANCED/UNBALANCED',
    baseline_low_upper DOUBLE NULL COMMENT '基线区间上界（低区间）',
    baseline_balanced_low DOUBLE NULL COMMENT '平衡区间下界',
    baseline_balanced_upper DOUBLE NULL COMMENT '平衡区间上界',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uniq_garmin_account_id_calendar_date (garmin_account_id, calendar_date),
    CONSTRAINT fk_hrv_record_account FOREIGN KEY (garmin_account_id) REFERENCES garmin_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='HRV 状态记录';

CREATE TABLE raw_snapshot (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    garmin_account_id BIGINT UNSIGNED NOT NULL COMMENT 'Garmin账号ID',
    data_type VARCHAR(32) NOT NULL COMMENT '数据类型：daily/sleep/hrv 等',
    snapshot_date DATE NOT NULL COMMENT '数据归属日期',
    content_hash CHAR(64) NOT NULL COMMENT '内容 SHA-256，用于去重',
    file_path VARCHAR(512) NOT NULL COMMENT '原始 JSON 相对路径',
    file_size BIGINT UNSIGNED NOT NULL COMMENT '文件字节数',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uniq_account_id_data_type_snapshot_date_content_hash (garmin_account_id, data_type, snapshot_date, content_hash),
    CONSTRAINT fk_raw_snapshot_account FOREIGN KEY (garmin_account_id) REFERENCES garmin_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='原始数据归档索引';

CREATE TABLE sync_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    sync_job_id BIGINT UNSIGNED NOT NULL COMMENT '同步任务ID',
    log_level VARCHAR(16) NOT NULL COMMENT '日志级别：INFO/WARN/ERROR',
    message VARCHAR(512) NOT NULL COMMENT '脱敏后的日志内容',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_sync_job_id (sync_job_id),
    CONSTRAINT fk_sync_log_job FOREIGN KEY (sync_job_id) REFERENCES sync_job (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='同步任务日志';
