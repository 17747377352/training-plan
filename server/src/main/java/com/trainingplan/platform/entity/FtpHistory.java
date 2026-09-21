package com.trainingplan.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 骑行 FTP 历史实体。
 *
 * @author gongxuesong
 * @date 2026-09-21
 */
@Data
@TableName("ftp_history")
public class FtpHistory {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** Garmin 账号 ID。 */
    private Long garminAccountId;

    /** 该 FTP 的生效日期。 */
    private LocalDate effectiveDate;

    /** 功能阈值功率（瓦）。 */
    private Integer ftpWatts;

    /** 来源：GARMIN 自动测算 / MANUAL 手工录入。 */
    private String source;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
