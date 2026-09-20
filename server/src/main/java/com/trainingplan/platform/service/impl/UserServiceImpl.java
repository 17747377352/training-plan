package com.trainingplan.platform.service.impl;

import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.user.UserProfileDto;
import com.trainingplan.platform.entity.SysUser;
import com.trainingplan.platform.mapper.SysRoleMapper;
import com.trainingplan.platform.mapper.SysUserMapper;
import com.trainingplan.platform.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 平台用户查询服务实现。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;

    @Override
    public UserProfileDto getProfile(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        List<String> roles = roleMapper.selectRoleCodesByUserId(userId);
        return new UserProfileDto(user.getId(), user.getUsername(), user.getEmail(), roles);
    }
}

