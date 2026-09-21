-- 午睡记录。
--
-- 此前采集器只读 dailySleepDTO 的主睡眠字段，napTimeSeconds 与 dailyNapDTOS
-- 一个都没映射，午睡数据全部丢弃。实测 2026-09-21 有一次 43 分钟午睡
-- （北京 13:27–14:10），库里只有夜间 5.3 小时。
--
-- 为什么单独建表而不是给 sleep_record 加一列：dailyNapDTOS 是**数组**，
-- 一天可能有多次午睡，单列只能存当天合计、会静默丢掉第二次；而且需要起点
-- 才能做幂等去重（与 sleep_record 用 sleep_start_gmt 去重同理）。
--
-- 重要：午睡**不参与判灯**。TrainingAdviceEngine 只接收 sleep_record，
-- 看不到这张表——判灯规则不变，这里只是不再丢数据。是否计入恢复判断是
-- 单独的规则决策，见 docs/训练建议规则.md。
CREATE TABLE nap_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    garmin_account_id BIGINT UNSIGNED NOT NULL COMMENT 'Garmin账号ID',
    calendar_date DATE NOT NULL COMMENT '数据归属日期',
    nap_start_gmt DATETIME NOT NULL COMMENT '午睡开始（GMT）',
    nap_end_gmt DATETIME NULL COMMENT '午睡结束（GMT）',
    nap_seconds INT NULL COMMENT '该次午睡时长（秒）',
    nap_feedback VARCHAR(64) NULL COMMENT 'Garmin 对这次午睡的评价短语',
    nap_source INT NULL COMMENT 'Garmin 的午睡来源标记',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uniq_garmin_account_id_nap_start_gmt (garmin_account_id, nap_start_gmt),
    KEY idx_garmin_account_id_calendar_date (garmin_account_id, calendar_date),
    CONSTRAINT fk_nap_record_account FOREIGN KEY (garmin_account_id) REFERENCES garmin_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='午睡记录';
