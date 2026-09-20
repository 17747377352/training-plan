package com.trainingplan.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户绑定的 Garmin 账号实体。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Data
@TableName("garmin_account")
public class GarminAccount {

    /** Garmin 账号主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 平台用户 ID。 */
    private Long userId;

    /** Garmin 站点区域：GLOBAL 国际站，CN 中国站。 */
    private String region;

    /** Garmin 邮箱 SHA-256 摘要，用于去重且避免明文入库。 */
    private String garminEmailHash;

    /** 脱敏 Garmin 邮箱。 */
    private String garminEmailMasked;

    /** AES-GCM 加密后的 Garmin Token。 */
    private String tokenCiphertext;

    /** 认证状态：PENDING、PENDING_MFA、ACTIVE、REAUTH_REQUIRED。 */
    private String authStatus;

    /** 是否允许自动同步：0 暂停，1 启用。 */
    private Integer syncEnabled;

    /** 最近同步时间。 */
    private LocalDateTime lastSyncTime;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
