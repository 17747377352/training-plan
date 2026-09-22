package com.trainingplan.platform.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 平台统一业务错误码。
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    SUCCESS(200, "success"),
    PARAM_ERROR(40000, "请求参数错误"),
    UNAUTHORIZED(40100, "未登录或登录已失效"),
    FORBIDDEN(40300, "没有操作权限"),
    NOT_FOUND(40400, "请求的数据不存在"),
    METHOD_NOT_ALLOWED(40500, "请求方法不支持"),
    BUSINESS_ERROR(40900, "业务处理失败"),
    USER_ALREADY_EXISTS(41001, "用户名或邮箱已被使用"),
    INVALID_CREDENTIALS(41002, "用户名或密码错误"),
    ACCOUNT_DISABLED(41003, "账号已被禁用"),
    INVALID_REFRESH_TOKEN(41004, "刷新令牌无效或已过期"),
    REGISTRATION_DISABLED(41006, "当前未开放注册，请联系管理员开通账号"),
    CANNOT_DISABLE_SELF(41005, "不能禁用当前登录的管理员账号"),
    GARMIN_AUTH_REQUIRED(46001, "Garmin账号需要重新认证"),
    GARMIN_RATE_LIMITED(46002, "Garmin接口请求过于频繁"),
    GARMIN_SYNC_ERROR(46003, "Garmin数据同步失败"),
    GARMIN_ACCOUNT_EXISTS(46004, "该Garmin账号已绑定"),
    GARMIN_COLLECTOR_UNAVAILABLE(46005, "Garmin采集服务暂不可用"),
    GARMIN_MFA_SESSION_EXPIRED(46006, "MFA验证会话已过期，请重新连接"),
    GARMIN_INVALID_CREDENTIALS(46007, "Garmin账号或密码错误"),
    GARMIN_CONNECT_FAILED(46008, "连接Garmin账号失败"),
    GARMIN_CONNECT_TIMEOUT(46009, "Garmin登录耗时过长已超时，请稍后重试"),
    SYNC_JOB_TIMEOUT(46010, "同步任务超时未完成"),
    GARMIN_PAIR_CODE_INVALID(46011, "配对码无效或已过期，请在页面上重新获取"),
    AI_SERVICE_ERROR(47001, "AI服务调用失败"),
    AI_TIMEOUT(47002, "AI服务响应超时"),
    AI_NOT_CONFIGURED(47003, "尚未配置 DeepSeek API Key，请在后端配置 DEEPSEEK_API_KEY 后重启服务"),
    AI_AUTH_ERROR(47004, "DeepSeek 鉴权失败，请检查后端 API Key"),
    AI_RATE_LIMITED(47005, "DeepSeek 请求过于频繁，请稍后手动重试"),
    AI_QUOTA_EXCEEDED(47007, "今日 AI 生成次数已用完，请明天再试"),
    AI_INVALID_RESPONSE(47006, "DeepSeek 返回的训练计划不完整或不符合当前恢复限制，请重新生成"),
    SYSTEM_ERROR(50000, "系统暂时不可用");

    private final Integer code;
    private final String message;
}
