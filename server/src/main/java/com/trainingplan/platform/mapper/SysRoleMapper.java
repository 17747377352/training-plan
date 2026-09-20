package com.trainingplan.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.trainingplan.platform.entity.SysRole;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 系统角色 Mapper，标准单表操作由 MyBatis-Plus 提供。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /**
     * 查询指定用户拥有的角色编码。
     *
     * @param userId 用户 ID
     * @return 角色编码列表
     */
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);
}
