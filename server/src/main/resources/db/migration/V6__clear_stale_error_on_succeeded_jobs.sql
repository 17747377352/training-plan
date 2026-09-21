-- 修复旧数据：状态为 SUCCESS 却留着失败原因的任务。
--
-- 原因：状态流转原先是不带条件的 updateById，队列里迟到的消息会把已经被判超时
-- 的任务重新推进成 SUCCESS，但 error_code / error_message 不会被清掉，于是看板上
-- 出现「成功 + Garmin接口请求过于频繁」这种自相矛盾的记录。
-- 代码侧已改为带状态条件的流转并在完成时显式清空错误列，这里把历史数据对齐。
UPDATE sync_job
SET error_code = NULL,
    error_message = NULL
WHERE job_status = 'SUCCESS'
  AND (error_code IS NOT NULL OR error_message IS NOT NULL);
