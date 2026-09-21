-- 同步任务看板需要展示「这次同步拉了哪段数据」和「为什么失败」，
-- 原先这两项只写进了应用日志，任务表本身查不到。
--
-- 数据区间在任务创建时就确定（end = 今天，start = end - (range - 1)），
-- 因此落地成列而不是事后按 create_time 推算——回溯天数今后可配置，
-- 事后推算会把历史任务算错。
ALTER TABLE sync_job
    ADD COLUMN start_date DATE NULL COMMENT '同步数据起始日期（含）' AFTER job_status,
    ADD COLUMN end_date DATE NULL COMMENT '同步数据结束日期（含）' AFTER start_date,
    ADD COLUMN error_message VARCHAR(512) NULL COMMENT '脱敏失败原因，取自错误码文案' AFTER error_code,
    ADD COLUMN retry_of_job_id BIGINT UNSIGNED NULL COMMENT '重试来源任务ID，非重试任务为空' AFTER error_message;

-- 看板按「当前用户的账号 + 时间倒序」翻页，走这个组合索引。
ALTER TABLE sync_job
    ADD KEY idx_garmin_account_id_create_time (garmin_account_id, create_time);

ALTER TABLE sync_job
    ADD CONSTRAINT fk_sync_job_retry_of FOREIGN KEY (retry_of_job_id) REFERENCES sync_job (id);
