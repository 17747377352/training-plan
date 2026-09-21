package com.trainingplan.platform.service;

import com.trainingplan.platform.dto.training.TrainingAdviceDto;
import java.time.LocalDate;

/** 汇总当前用户数据并生成可解释的单日骑行处方。 */
public interface TrainingAdviceService {
    TrainingAdviceDto getAdvice(Long userId, LocalDate date);
}
