package com.trainingplan.platform.dto.admin;

import lombok.Data;
import lombok.EqualsAndHashCode;

import com.trainingplan.platform.common.api.PageParam;

/**
 * 管理员查询平台用户的分页条件。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AdminUserQuery extends PageParam {

    /** 关键字，按用户名或邮箱模糊匹配。 */
    private String keyword;

    /** 用户状态：0 禁用，1 启用；为空时不过滤。 */
    private Integer status;
}
