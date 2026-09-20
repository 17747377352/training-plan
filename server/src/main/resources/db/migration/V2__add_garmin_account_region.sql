-- Garmin 国际站与国区站点是两套独立账号体系，同一邮箱可能在两个站点各自注册，
-- 因此区域必须参与账号唯一性判定，否则同一邮箱的跨站账号会互相覆盖。
ALTER TABLE garmin_account
    ADD COLUMN region VARCHAR(16) NOT NULL DEFAULT 'GLOBAL' COMMENT 'Garmin站点区域：GLOBAL国际站，CN中国站' AFTER user_id;

ALTER TABLE garmin_account
    DROP INDEX uniq_user_id_garmin_email_hash,
    ADD UNIQUE KEY uniq_user_id_region_garmin_email_hash (user_id, region, garmin_email_hash);
