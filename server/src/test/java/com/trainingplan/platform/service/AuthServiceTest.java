package com.trainingplan.platform.service;

import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.auth.AuthTokenDto;
import com.trainingplan.platform.dto.auth.LoginRequest;
import com.trainingplan.platform.dto.auth.RegisterRequest;
import com.trainingplan.platform.entity.SysUser;
import com.trainingplan.platform.mapper.SysRoleMapper;
import com.trainingplan.platform.mapper.SysUserMapper;
import com.trainingplan.platform.mapper.SysUserRoleMapper;
import com.trainingplan.platform.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private SysUserMapper userMapper;
    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private SysUserRoleMapper userRoleMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private TokenService tokenService;
    @Mock
    private UserService userService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                userMapper, roleMapper, userRoleMapper, passwordEncoder, tokenService, userService);
        // 单测没有 Spring 容器，@Value 字段保持默认值 false。注册相关用例显式打开，
        // 关闭场景由下面的用例单独覆盖。
        ReflectionTestUtils.setField(authService, "registrationEnabled", true);
    }

    @Test
    void shouldRejectRegistrationWhenDisabled() {
        ReflectionTestUtils.setField(authService, "registrationEnabled", false);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("runner01", "runner@example.com", "password123")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REGISTRATION_DISABLED);

        // 关闭注册时连用户名重复检查都不该做
        verify(userMapper, never()).selectCount(any());
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void reportsRegistrationSwitchState() {
        assertThat(authService.isRegistrationEnabled()).isTrue();
        ReflectionTestUtils.setField(authService, "registrationEnabled", false);
        assertThat(authService.isRegistrationEnabled()).isFalse();
    }

    @Test
    void shouldRejectDuplicateUsernameOrEmail() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("runner01", "runner@example.com", "password123")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_ALREADY_EXISTS);
    }

    @Test
    void shouldRejectWrongPassword() {
        SysUser user = enabledUser();
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("wrong-password", user.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("runner01", "wrong-password")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void shouldIssueTokensForEnabledUser() {
        SysUser user = enabledUser();
        AuthTokenDto expected = new AuthTokenDto("Bearer", "access", "refresh", 900L);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("password123", user.getPasswordHash())).thenReturn(true);
        when(roleMapper.selectRoleCodesByUserId(user.getId())).thenReturn(List.of("USER"));
        when(tokenService.issueTokens(user, List.of("USER"))).thenReturn(expected);

        AuthTokenDto actual = authService.login(new LoginRequest("runner01", "password123"));

        assertThat(actual).isEqualTo(expected);
        verify(tokenService).issueTokens(user, List.of("USER"));
    }

    private SysUser enabledUser() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("runner01");
        user.setEmail("runner@example.com");
        user.setPasswordHash("encoded-password");
        user.setStatus(1);
        return user;
    }
}
