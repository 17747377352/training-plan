package com.trainingplan.platform.controller;

import com.trainingplan.platform.common.api.Result;
import com.trainingplan.platform.dto.garmin.BrowserUploadPairRequest;
import com.trainingplan.platform.dto.garmin.BrowserUploadPairResult;
import com.trainingplan.platform.dto.garmin.BrowserUploadRequest;
import com.trainingplan.platform.service.BrowserUploadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本机浏览器取数后的配对、上传与撤销入口。
 *
 * <p>配对使用一次性码，上传使用专用凭据，撤销使用平台 JWT；所有结果统一返回 Result。</p>
 *
 * @author gongxuesong
 * @date 2026-09-24
 */
@RestController
@RequestMapping("/api/garmin/browser-upload")
@RequiredArgsConstructor
public class BrowserUploadController {

    private final BrowserUploadService service;

    /** 消费配对码，为本机助手签发只绑定一个账号的上传凭据。 */
    @PostMapping("/pair")
    public Result<BrowserUploadPairResult> pair(@Valid @RequestBody BrowserUploadPairRequest request) {
        return Result.success(service.pair(request));
    }

    /** 成功回执包含已完成的任务 ID；业务失败仍须读取 Result.code 判断。 */
    @PostMapping("/ingest")
    public Result<Long> ingest(@RequestHeader(value = "X-Garmin-Upload-Token", required = false) String token,
                               @RequestBody BrowserUploadRequest request) {
        return Result.success(service.ingest(token, request));
    }

    /** 平台登录用户只能撤销自己账号的凭据。 */
    @DeleteMapping("/credentials/{accountId}")
    public Result<Void> revoke(@PathVariable Long accountId, @AuthenticationPrincipal Jwt jwt) {
        service.revoke(Long.valueOf(jwt.getSubject()), accountId);
        return Result.success(null);
    }
}
