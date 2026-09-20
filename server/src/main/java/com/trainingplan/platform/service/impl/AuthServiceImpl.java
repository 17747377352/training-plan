package com.trainingplan.platform.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.auth.AuthTokenDto;
import com.trainingplan.platform.dto.auth.LoginRequest;
import com.trainingplan.platform.dto.auth.RegisterRequest;
import com.trainingplan.platform.dto.user.UserProfileDto;
import com.trainingplan.platform.entity.SysRole;
import com.trainingplan.platform.entity.SysUser;
import com.trainingplan.platform.entity.SysUserRole;
import com.trainingplan.platform.mapper.SysRoleMapper;
import com.trainingplan.platform.mapper.SysUserMapper;
import com.trainingplan.platform.mapper.SysUserRoleMapper;
import com.trainingplan.platform.service.AuthService;
import com.trainingplan.platform.service.TokenService;
import com.trainingplan.platform.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * 用户注册和认证服务实现。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_ROLE_CODE = "USER";

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final UserService userService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserProfileDto register(RegisterRequest request) {
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        Long duplicateCount = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username)
                .or()
                .eq(SysUser::getEmail, email));
        if (duplicateCount > 0) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
        }

        SysRole userRole = roleMapper.selectOne(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getRoleCode, DEFAULT_ROLE_CODE));
        if (userRole == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "系统默认角色未初始化");
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStatus(1);
        userMapper.insert(user);

        SysUserRole relation = new SysUserRole();
        relation.setUserId(user.getId());
        relation.setRoleId(userRole.getId());
        userRoleMapper.insert(relation);
        log.info("用户注册成功 userId={}", user.getId());
        return new UserProfileDto(user.getId(), user.getUsername(), user.getEmail(), List.of(DEFAULT_ROLE_CODE));
    }

    @Override
    public AuthTokenDto login(LoginRequest request) {
        String account = request.account().trim();
        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, account)
                .or()
                .eq(SysUser::getEmail, account.toLowerCase(Locale.ROOT)));
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        List<String> roles = roleMapper.selectRoleCodesByUserId(user.getId());
        log.info("用户登录成功 userId={}", user.getId());
        return tokenService.issueTokens(user, roles);
    }

    @Override
    public AuthTokenDto refresh(String refreshToken) {
        Long userId = tokenService.consumeRefreshToken(refreshToken);
        UserProfileDto profile = userService.getProfile(userId);
        SysUser user = userMapper.selectById(userId);
        return tokenService.issueTokens(user, profile.roles());
    }

    @Override
    public void logout(String refreshToken) {
        tokenService.revokeRefreshToken(refreshToken);
    }
}

