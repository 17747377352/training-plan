CREATE TABLE browser_upload_credential (
    garmin_account_id BIGINT UNSIGNED NOT NULL COMMENT '绑定的Garmin账号',
    token_hash CHAR(64) NOT NULL COMMENT '上传令牌SHA-256摘要',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (garmin_account_id),
    UNIQUE KEY uniq_browser_upload_token_hash (token_hash),
    CONSTRAINT fk_browser_upload_account FOREIGN KEY (garmin_account_id)
        REFERENCES garmin_account (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本机浏览器上传凭据';
