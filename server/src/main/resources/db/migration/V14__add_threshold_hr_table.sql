-- 心率阈值（Garmin 的乳酸阈值心率）单独存历史。
-- 它是「最新值 + 变更历史」型指标：Garmin 只给稀疏的若干次记录（实测跑步系列
-- 2026-08-22 = 179、2026-08-29 = 178），而且**只有跑步系列**，骑行阈值心率 Garmin 侧为空。
-- 因此按 (账号, 系列, 生效日期) 去重，与 ftp_history 同一套写法。
CREATE TABLE threshold_hr (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    garmin_account_id BIGINT UNSIGNED NOT NULL COMMENT 'Garmin账号ID',
    effective_date DATE NOT NULL COMMENT '该阈值心率的生效日期',
    series VARCHAR(16) NOT NULL COMMENT '系列：RUNNING / CYCLING',
    heart_rate INT NOT NULL COMMENT '阈值心率（bpm）',
    source VARCHAR(32) NOT NULL DEFAULT 'GARMIN' COMMENT '来源：GARMIN 接口测得 / MANUAL 手工录入',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uniq_account_series_effective_date (garmin_account_id, series, effective_date),
    CONSTRAINT fk_threshold_hr_account FOREIGN KEY (garmin_account_id) REFERENCES garmin_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='阈值心率历史';
