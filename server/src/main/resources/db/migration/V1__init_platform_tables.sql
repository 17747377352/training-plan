CREATE TABLE sys_user (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户主键',
    username VARCHAR(64) NOT NULL COMMENT '登录用户名',
    email VARCHAR(128) NULL COMMENT '用户邮箱',
    password_hash VARCHAR(255) NOT NULL COMMENT 'BCrypt密码摘要',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用，1启用',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uniq_username (username),
    UNIQUE KEY uniq_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台用户';

CREATE TABLE sys_role (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '角色主键',
    role_code VARCHAR(32) NOT NULL COMMENT '角色编码',
    role_name VARCHAR(64) NOT NULL COMMENT '角色名称',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uniq_role_code (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统角色';

CREATE TABLE sys_user_role (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '关联主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    role_id BIGINT UNSIGNED NOT NULL COMMENT '角色ID',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uniq_user_id_role_id (user_id, role_id),
    KEY idx_role_id (role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关系';

CREATE TABLE garmin_account (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Garmin账号主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '平台用户ID',
    garmin_email_hash CHAR(64) NOT NULL COMMENT 'Garmin邮箱SHA-256摘要',
    garmin_email_masked VARCHAR(128) NOT NULL COMMENT '脱敏Garmin邮箱',
    token_ciphertext TEXT NULL COMMENT '加密后的Garmin Token',
    auth_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '认证状态',
    sync_enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否允许自动同步',
    last_sync_time DATETIME(3) NULL COMMENT '最近同步时间',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uniq_user_id_garmin_email_hash (user_id, garmin_email_hash),
    KEY idx_auth_status (auth_status),
    CONSTRAINT fk_garmin_account_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户Garmin账号';

CREATE TABLE sync_job (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '同步任务主键',
    garmin_account_id BIGINT UNSIGNED NOT NULL COMMENT 'Garmin账号ID',
    job_type VARCHAR(32) NOT NULL COMMENT '任务类型',
    job_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态',
    requested_by BIGINT UNSIGNED NULL COMMENT '发起用户ID，定时任务为空',
    started_time DATETIME(3) NULL COMMENT '开始时间',
    finished_time DATETIME(3) NULL COMMENT '完成时间',
    error_code VARCHAR(64) NULL COMMENT '脱敏错误编码',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_garmin_account_id_job_status (garmin_account_id, job_status),
    KEY idx_create_time (create_time),
    CONSTRAINT fk_sync_job_account FOREIGN KEY (garmin_account_id) REFERENCES garmin_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Garmin同步任务';

INSERT INTO sys_role (role_code, role_name) VALUES ('ADMIN', '管理员'), ('USER', '普通用户');

