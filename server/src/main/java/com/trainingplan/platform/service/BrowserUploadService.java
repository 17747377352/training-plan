package com.trainingplan.platform.service;

import com.trainingplan.platform.dto.garmin.BrowserUploadPairRequest;
import com.trainingplan.platform.dto.garmin.BrowserUploadPairResult;
import com.trainingplan.platform.dto.garmin.BrowserUploadRequest;

/**
 * 本机浏览器采集数据的上传服务。
 *
 * @author gongxuesong
 * @date 2026-09-24
 */
public interface BrowserUploadService {

    /**
     * 消费一次性配对码，为所属平台用户创建或切换 Garmin 账号并签发上传凭据。
     * 重新配对会撤销旧上传凭据；此接口不接收 Garmin 密码或 Cookie。
     *
     * @param request 配对码、Garmin 邮箱与站点
     * @return 账号 ID 和仅返回一次的上传令牌
     */
    BrowserUploadPairResult pair(BrowserUploadPairRequest request);

    /**
     * 校验专用凭据，将一个日期批次在同一事务中入库并记为成功任务。
     * 调用方不能指定用户或账号，归属只能由凭据确定。
     *
     * @param token 专用上传令牌
     * @param request 日期范围与规范化后的业务数据
     * @return 已完成的同步任务 ID
     */
    Long ingest(String token, BrowserUploadRequest request);

    /**
     * 撤销当前用户自有账号的上传凭据。
     *
     * @param userId 当前平台登录用户
     * @param accountId Garmin 账号 ID
     */
    void revoke(Long userId, Long accountId);
}
