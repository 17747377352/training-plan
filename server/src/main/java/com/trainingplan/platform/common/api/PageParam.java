package com.trainingplan.platform.common.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 分页请求基类，所有分页查询 DTO 均继承本类。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Data
public class PageParam {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /** 页码，从 1 开始。 */
    @Min(value = 1, message = "页码不能小于1")
    private Integer page = DEFAULT_PAGE;

    /** 每页条数。 */
    @Min(value = 1, message = "每页条数不能小于1")
    @Max(value = MAX_PAGE_SIZE, message = "每页条数不能超过100")
    private Integer size = DEFAULT_PAGE_SIZE;

    /**
     * 返回生效的页码，未传值时使用默认值。
     *
     * @return 页码
     */
    public int currentPage() {
        return page == null ? DEFAULT_PAGE : page;
    }

    /**
     * 返回生效的每页条数，未传值时使用默认值。
     *
     * @return 每页条数
     */
    public int pageSize() {
        return size == null ? DEFAULT_PAGE_SIZE : size;
    }
}
