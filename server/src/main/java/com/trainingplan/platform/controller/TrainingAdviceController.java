package com.trainingplan.platform.controller;

import com.trainingplan.platform.common.api.Result;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.common.exception.BusinessException;
import com.trainingplan.platform.dto.training.TrainingAdviceDto;
import com.trainingplan.platform.service.TrainingAdviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** 今日或历史单日建议，只接受日期，数据归属由登录身份确定。 */
@RestController
@RequestMapping("/api/training-advice")
@RequiredArgsConstructor
public class TrainingAdviceController {
    private final TrainingAdviceService service;

    /** 不传日期时按上海时区评估今天；历史日期用于回看，不预测未来恢复状态。 */
    @GetMapping
    public Result<TrainingAdviceDto> getAdvice(@RequestParam(required = false) String date,
                                              @AuthenticationPrincipal Jwt jwt) {
        Long userId;
        try {
            userId = Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        LocalDate day;
        try {
            day = date == null ? null : LocalDate.parse(date);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "日期格式应为 YYYY-MM-DD");
        }
        return Result.success(service.getAdvice(userId, day));
    }
}
