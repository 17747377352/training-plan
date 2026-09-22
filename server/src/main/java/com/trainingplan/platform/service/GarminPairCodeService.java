package com.trainingplan.platform.service;

import com.trainingplan.platform.dto.garmin.PairCodeDto;

/**
 * 桌面助手配对码服务。
 *
 * @author gongxuesong
 * @date 2026-09-22
 */
public interface GarminPairCodeService {

    /**
     * 为已登录用户签发一个一次性配对码。
     *
     * @param userId 平台用户 ID
     * @return 配对码与有效期
     */
    PairCodeDto issue(Long userId);

    /**
     * 取用配对码，返回它所属的平台用户；取用即失效。
     *
     * @param code 配对码
     * @return 平台用户 ID
     */
    Long consume(String code);
}
