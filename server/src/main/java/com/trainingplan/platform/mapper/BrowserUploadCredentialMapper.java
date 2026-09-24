package com.trainingplan.platform.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * 浏览器上传凭据 Mapper；自定义 SQL 统一维护在同名 XML 中。
 *
 * @author gongxuesong
 * @date 2026-09-24
 */
public interface BrowserUploadCredentialMapper {

    /** 切换到本机浏览器采集，清除服务器已持有的 Garmin DI 令牌。 */
    int activateBrowserAccount(@Param("accountId") Long accountId);

    /** 签发或轮换凭据；数据库只存不可逆摘要，明文仅返回本机一次。 */
    int upsertCredential(@Param("accountId") Long accountId, @Param("tokenHash") String tokenHash);

    /**
     * 按摘要验证凭据并锁定该行，令同一账号的入库、轮换与撤销串行执行。
     * 必须在事务中调用，避免并发上传在「查询后插入」时争抢同一个业务唯一键。
     */
    Long selectAccountIdByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /** 撤销指定账号的上传权限。账号归属由服务层先行校验。 */
    int deleteByAccountId(@Param("accountId") Long accountId);
}
