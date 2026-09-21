-- 骑行活动表。
--
-- 字段名依据 2026-09-21 对真实接口返回的实地核对（get_activities_by_date），
-- 而非 Garmin 网页导出的中文列名。几处容易写错的地方：
--   * 踏频字段是 averageBikingCadenceInRevPerMinute，不是 avgCadence；
--   * 功率区间与左右平衡**就在活动列表里**（powerTimeInZone_*、avgLeftBalance），
--     不需要额外调用活动详情接口；
--   * 类型用 type_key 存储而非中文显示名：网页导出里的「公路骑行」是本地化文案，
--     换语言就变，type_key（如 road_biking）才是稳定标识。
--
-- 刻意不存的字段（开发计划 §12 要求日志与存储脱敏 GPS 与个人信息）：
--   locationName、startLatitude/endLatitude/startLongitude/endLongitude、
--   ownerFullName/ownerId/ownerDisplayName。需要轨迹功能时再单独设计。

CREATE TABLE activity (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    garmin_account_id BIGINT UNSIGNED NOT NULL COMMENT 'Garmin账号ID',
    garmin_activity_id BIGINT UNSIGNED NOT NULL COMMENT 'Garmin活动ID',
    activity_type_key VARCHAR(64) NOT NULL COMMENT '活动类型稳定标识，如 road_biking',
    activity_type_id INT NULL COMMENT '活动类型ID',
    parent_type_id INT NULL COMMENT '父类型ID，用于归并同类活动',
    activity_name VARCHAR(255) NULL COMMENT '活动名称，用户可修改',
    start_time_gmt DATETIME(3) NULL COMMENT '开始时间（GMT）',
    start_time_local DATETIME(3) NULL COMMENT '开始时间（本地）',
    duration_seconds INT NULL COMMENT '计时时长（秒）',
    moving_duration_seconds INT NULL COMMENT '移动时长（秒）',
    elapsed_duration_seconds INT NULL COMMENT '总耗时（秒）',
    distance_meters DOUBLE NULL COMMENT '距离（米）',
    elevation_gain DOUBLE NULL COMMENT '累计爬升（米）',
    elevation_loss DOUBLE NULL COMMENT '累计下降（米）',
    avg_elevation DOUBLE NULL COMMENT '平均海拔（米）',
    max_elevation DOUBLE NULL COMMENT '最高海拔（米）',
    min_elevation DOUBLE NULL COMMENT '最低海拔（米）',
    average_speed DOUBLE NULL COMMENT '平均速度（米/秒）',
    max_speed DOUBLE NULL COMMENT '最大速度（米/秒）',
    average_hr INT NULL COMMENT '平均心率（bpm）',
    max_hr INT NULL COMMENT '最大心率（bpm）',
    calories DOUBLE NULL COMMENT '活动热量（千卡）',
    bmr_calories DOUBLE NULL COMMENT '基础代谢热量（千卡）',
    avg_power DOUBLE NULL COMMENT '平均功率（瓦）',
    max_power DOUBLE NULL COMMENT '最大功率（瓦）',
    norm_power DOUBLE NULL COMMENT '标准化功率 NP（瓦）',
    max_20min_power DOUBLE NULL COMMENT '20 分钟最大平均功率（瓦）',
    intensity_factor DOUBLE NULL COMMENT '强度因子 IF',
    training_stress_score DOUBLE NULL COMMENT '训练压力分数 TSS',
    avg_cadence DOUBLE NULL COMMENT '平均踏频（转/分）',
    max_cadence DOUBLE NULL COMMENT '最大踏频（转/分）',
    avg_left_balance DOUBLE NULL COMMENT '左侧发力占比（%）',
    aerobic_training_effect DOUBLE NULL COMMENT '有氧训练效果',
    anaerobic_training_effect DOUBLE NULL COMMENT '无氧训练效果',
    training_effect_label VARCHAR(64) NULL COMMENT '训练效果标签',
    activity_training_load DOUBLE NULL COMMENT '活动训练负荷',
    power_zone_1_seconds DOUBLE NULL COMMENT '功率区间1时长（秒）',
    power_zone_2_seconds DOUBLE NULL COMMENT '功率区间2时长（秒）',
    power_zone_3_seconds DOUBLE NULL COMMENT '功率区间3时长（秒）',
    power_zone_4_seconds DOUBLE NULL COMMENT '功率区间4时长（秒）',
    power_zone_5_seconds DOUBLE NULL COMMENT '功率区间5时长（秒）',
    power_zone_6_seconds DOUBLE NULL COMMENT '功率区间6时长（秒）',
    power_zone_7_seconds DOUBLE NULL COMMENT '功率区间7时长（秒）',
    lap_count INT NULL COMMENT '圈数',
    strokes DOUBLE NULL COMMENT '总踩踏圈数',
    avg_respiration_rate DOUBLE NULL COMMENT '平均呼吸频率（次/分）',
    min_temperature DOUBLE NULL COMMENT '最低温度（摄氏度）',
    max_temperature DOUBLE NULL COMMENT '最高温度（摄氏度）',
    vo2max_value DOUBLE NULL COMMENT '活动时的 VO2max 估算',
    device_id BIGINT NULL COMMENT '记录设备ID',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uniq_garmin_account_id_garmin_activity_id (garmin_account_id, garmin_activity_id),
    KEY idx_activity_type_key (activity_type_key),
    KEY idx_start_time_gmt (start_time_gmt),
    CONSTRAINT fk_activity_account FOREIGN KEY (garmin_account_id) REFERENCES garmin_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='骑行活动记录';
