package com.trainingplan.platform.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/**
 * 分页响应统一结构。
 *
 * @param total   总记录数
 * @param page    当前页码
 * @param size    每页条数
 * @param records 当前页数据
 * @param <T>     数据类型
 * @author gongxuesong
 * @date 2026-09-20
 */
public record PageResult<T>(long total, long page, long size, List<T> records) {

    /**
     * 由 MyBatis-Plus 分页对象构造统一分页响应。
     *
     * @param source MyBatis-Plus 分页结果
     * @param <T>    数据类型
     * @return 统一分页响应
     */
    public static <T> PageResult<T> of(IPage<T> source) {
        return new PageResult<>(source.getTotal(), source.getCurrent(), source.getSize(), source.getRecords());
    }
}
