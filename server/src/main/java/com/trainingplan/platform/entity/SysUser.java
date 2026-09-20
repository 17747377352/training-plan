package com.trainingplan.platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台用户实体。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Data
@TableName("sys_user")
public class SysUser {

    /** 用户主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录用户名。 */
    private String username;

    /** 用户邮箱。 */
    private String email;

    /** BCrypt 密码摘要。 */
    private String passwordHash;

    /** 状态：0 禁用，1 启用。 */
    private Integer status;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}

