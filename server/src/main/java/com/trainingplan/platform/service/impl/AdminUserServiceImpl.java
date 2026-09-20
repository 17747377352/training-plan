package com.trainingplan.platform.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import com.trainingplan.platform.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理员用户管理服务实现。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {

    private static final int STATUS_DISABLED = 0;

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;

    @Override
    public PageResult<AdminUserDto> listUsers(AdminUserQuery query) {
        LambdaQueryWrapper<SysUser> wrapper = Wrappers.<SysUser>lambdaQuery()
                .eq(query.getStatus() != null, SysUser::getStatus, query.getStatus())
                .orderByDesc(SysUser::getId);
        String keyword = query.getKeyword() == null ? null : query.getKeyword().trim();
        if (StringUtils.hasText(keyword)) {
            wrapper.and(condition -> condition
                    .like(SysUser::getUsername, keyword)
                    .or()
                    .like(SysUser::getEmail, keyword));
        }

        IPage<SysUser> page = userMapper.selectPage(
                Page.of(query.currentPage(), query.pageSize()), wrapper);
        Map<Long, List<String>> rolesByUserId = loadRolesByUserId(page.getRecords());
        List<AdminUserDto> records = page.getRecords().stream()
                .map(user -> new AdminUserDto(
                        user.getId(),
                        user.getUsername(),
                        user.getEmail(),
                        user.getStatus(),
                        rolesByUserId.getOrDefault(user.getId(), List.of()),
                        user.getCreateTime()))
                .toList();
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), records);
    }

    @Override
    public void updateUserStatus(Long userId, Integer status, Long operatorId) {
        if (status != null && STATUS_DISABLED == status && userId.equals(operatorId)) {
            throw new BusinessException(ErrorCode.CANNOT_DISABLE_SELF);
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (status.equals(user.getStatus())) {
            return;
        }
        SysUser update = new SysUser();
        update.setId(userId);
        update.setStatus(status);
        userMapper.updateById(update);
        log.info("平台用户状态已变更 userId={} status={} operatorId={}", userId, status, operatorId);
    }

    /**
     * 批量装配用户角色，避免逐行查询造成 N+1。
     *
     * @param users 当前页用户
     * @return 用户 ID 到角色编码列表的映射
     */
    private Map<Long, List<String>> loadRolesByUserId(List<SysUser> users) {
        if (users.isEmpty()) {
            return Map.of();
        }
        List<Long> userIds = users.stream().map(SysUser::getId).toList();
        return roleMapper.selectRoleCodesByUserIds(userIds).stream()
                .collect(Collectors.groupingBy(
                        UserRoleCodeRow::userId,
                        Collectors.mapping(UserRoleCodeRow::roleCode, Collectors.toList())));
    }
}
