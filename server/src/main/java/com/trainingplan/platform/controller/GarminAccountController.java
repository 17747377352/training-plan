package com.trainingplan.platform.controller;

import com.trainingplan.platform.common.api.Result;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.garmin.ConnectGarminMfaRequest;
import com.trainingplan.platform.dto.garmin.ConnectGarminRequest;
import com.trainingplan.platform.dto.garmin.GarminAccountDto;
import com.trainingplan.platform.dto.garmin.GarminConnectResultDto;
import com.trainingplan.platform.dto.garmin.ImportTokenRequest;
import com.trainingplan.platform.dto.garmin.UpdateAutoSyncRequest;
import com.trainingplan.platform.service.GarminAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Garmin 账号绑定与维护接口。
 *
 * <p>所有接口只操作当前登录用户自己的账号，账号归属由令牌中的用户 ID 决定，
 * 不接受调用方传入的用户 ID。</p>
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@RestController
@RequestMapping("/api/garmin/accounts")
@RequiredArgsConstructor
public class GarminAccountController {

    private final GarminAccountService garminAccountService;

    /**
     * 查询当前用户已绑定的 Garmin 账号。
     *
     * @param jwt 当前登录令牌
     * @return 账号列表
     */
    @GetMapping
    public Result<List<GarminAccountDto>> listAccounts(@AuthenticationPrincipal Jwt jwt) {
        return Result.success(garminAccountService.listAccounts(currentUserId(jwt)));
    }

    /**
     * 使用 Garmin 凭据发起连接。
     *
     * @param request 连接请求
     * @param jwt     当前登录令牌
     * @return 连接结果，可能需要继续提交 MFA 验证码
     */
    @PostMapping("/connect")
    public Result<GarminConnectResultDto> connect(@Valid @RequestBody ConnectGarminRequest request,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return Result.success(garminAccountService.connect(currentUserId(jwt), request));
    }

    /**
     * 提交 MFA 验证码完成连接。
     *
     * @param request MFA 请求
     * @param jwt     当前登录令牌
     * @return 连接结果
     */
    @PostMapping("/connect/mfa")
    public Result<GarminConnectResultDto> submitMfa(@Valid @RequestBody ConnectGarminMfaRequest request,
                                                    @AuthenticationPrincipal Jwt jwt) {
        return Result.success(garminAccountService.submitMfa(currentUserId(jwt), request));
    }

    /**
     * 导入已有令牌完成绑定，用于程序登录被 Garmin 限流时。
     *
     * @param request 令牌导入请求
     * @param jwt     当前登录令牌
     * @return 绑定后的账号信息
     */
    @PostMapping("/import-token")
    public Result<GarminAccountDto> importToken(@Valid @RequestBody ImportTokenRequest request,
                                                @AuthenticationPrincipal Jwt jwt) {
        return Result.success(garminAccountService.importToken(currentUserId(jwt), request));
    }

    /**
     * 用已存令牌校验账号是否仍然可用。
     *
     * @param id  账号 ID
     * @param jwt 当前登录令牌
     * @return 校验后的账号信息
     */
    @PostMapping("/{id}/verify")
    public Result<GarminAccountDto> verifyAccount(@PathVariable Long id,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return Result.success(garminAccountService.verifyAccount(currentUserId(jwt), id));
    }

    /**
     * 启用或暂停账号自动同步。
     *
     * @param id      账号 ID
     * @param request 开关请求
     * @param jwt     当前登录令牌
     * @return 空响应
     */
    @PutMapping("/{id}/auto-sync")
    public Result<Void> updateAutoSync(@PathVariable Long id,
                                       @Valid @RequestBody UpdateAutoSyncRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        garminAccountService.updateAutoSync(currentUserId(jwt), id, request.syncEnabled());
        return Result.success(null);
    }

    /**
     * 删除账号绑定。
     *
     * @param id  账号 ID
     * @param jwt 当前登录令牌
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteAccount(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        garminAccountService.deleteAccount(currentUserId(jwt), id);
        return Result.success(null);
    }

    /**
     * 从令牌主题解析当前用户 ID。
     *
     * @param jwt 当前登录令牌
     * @return 用户 ID
     */
    private Long currentUserId(Jwt jwt) {
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
