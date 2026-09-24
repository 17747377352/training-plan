-- V7 建的 ftp_history.source 只写了「GARMIN 自动测算 / MANUAL 手工录入」两种取值。
-- 现在多了一种：DERIVED —— 本机浏览器上传拿不到 Garmin 的 FTP 接口，只能按 Garmin 自己的
-- IF 定义（IF = NP / FTP）从活动功率反解，实测与真值相差不到 1 W，但它是反解值而不是
-- Garmin 报出的值，必须能一眼区分，所以把这个取值补进列注释。
-- 只改注释，不动类型与默认值。
ALTER TABLE ftp_history
    MODIFY COLUMN source VARCHAR(32) NOT NULL DEFAULT 'GARMIN'
        COMMENT '来源：GARMIN 接口测得 / DERIVED 由 NP/IF 反解 / MANUAL 手工录入';
