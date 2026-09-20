package com.trainingplan.platform.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.trainingplan.platform.common.api.PageResult;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.admin.AdminUserDto;
import com.trainingplan.platform.dto.admin.AdminUserQuery;
import com.trainingplan.platform.dto.admin.UserRoleCodeRow;
import com.trainingplan.platform.entity.SysUser;
import com.trainingplan.platform.mapper.SysRoleMapper;
import com.trainingplan.platform.mapper.SysUserMapper;
import com.trainingplan.platform.service.impl.AdminUserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock
    private SysUserMapper userMapper;
    @Mock
    private SysRoleMapper roleMapper;

    private AdminUserService adminUserService;

    @BeforeEach
    void setUp() {
        adminUserService = new AdminUserServiceImpl(userMapper, roleMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldAssembleRolesForEachUserInPage() {
        Page<SysUser> page = new Page<>(1, 20);
        page.setRecords(List.of(user(1L, "runner01", 1), user(2L, "runner02", 1)));
        page.setTotal(2L);
        when(userMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(page);
        when(roleMapper.selectRoleCodesByUserIds(List.of(1L, 2L))).thenReturn(List.of(
                new UserRoleCodeRow(1L, "USER"),
                new UserRoleCodeRow(2L, "ADMIN"),
                new UserRoleCodeRow(2L, "USER")));

        PageResult<AdminUserDto> result = adminUserService.listUsers(new AdminUserQuery());

        assertThat(result.total()).isEqualTo(2L);
        assertThat(result.page()).isEqualTo(1L);
        assertThat(result.records()).hasSize(2);
        assertThat(result.records().get(0).roles()).containsExactly("USER");
        assertThat(result.records().get(1).roles()).containsExactly("ADMIN", "USER");
    }

    @Test
    void shouldNotQueryRolesWhenPageIsEmpty() {
        Page<SysUser> emptyPage = new Page<>(1, 20);
        emptyPage.setRecords(List.of());
        when(userMapper.selectPage(any(IPage.class), any(Wrapper.class))).thenReturn(emptyPage);

        PageResult<AdminUserDto> result = adminUserService.listUsers(new AdminUserQuery());

        assertThat(result.records()).isEmpty();
        verify(roleMapper, never()).selectRoleCodesByUserIds(any());
    }

    @Test
    void shouldRejectDisablingCurrentAdmin() {
        assertThatThrownBy(() -> adminUserService.updateUserStatus(7L, 0, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CANNOT_DISABLE_SELF);

        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void shouldAllowAdminToDisableAnotherUser() {
        when(userMapper.selectById(8L)).thenReturn(user(8L, "runner02", 1));

        adminUserService.updateUserStatus(8L, 0, 7L);

        verify(userMapper).updateById(any(SysUser.class));
    }

    @Test
    void shouldIgnoreUpdateWhenStatusUnchanged() {
        when(userMapper.selectById(8L)).thenReturn(user(8L, "runner02", 1));

        adminUserService.updateUserStatus(8L, 1, 7L);

        verify(userMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void shouldRejectUnknownUser() {
        when(userMapper.selectById(anyLong())).thenReturn(null);

        assertThatThrownBy(() -> adminUserService.updateUserStatus(99L, 0, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    private SysUser user(Long id, String username, int status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setStatus(status);
        return user;
    }
}
