package com.trainingplan.platform.service;

import com.trainingplan.platform.service.impl.AiUsageServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用量写入必须是独立事务。
 *
 * <p>这是个只能靠注解表达、单测无法直接观察的性质：调用方 {@code generate} 带
 * {@code @Transactional}，失败时抛异常回滚；若用量日志写在同一事务里，失败记录
 * 会被一起回滚——实测就是这样：接口返回 AI_INVALID_RESPONSE，而日志表一行都没有，
 * 于是配额少算、花费少报。这里用反射把它钉住，防止有人顺手删掉注解。</p>
 *
 * @author gongxuesong
 * @date 2026-09-22
 */
class AiUsageTransactionTest {

    @Test
    void everyUsageWriteUsesItsOwnTransaction() throws Exception {
        for (String name : new String[]{"recordSuccess", "recordFailure", "recordReuse", "usage"}) {
            Method method = null;
            for (Method candidate : AiUsageServiceImpl.class.getMethods()) {
                if (candidate.getName().equals(name)) {
                    method = candidate;
                    break;
                }
            }
            assertThat(method).as("找不到方法 %s", name).isNotNull();
            Transactional annotation = method.getAnnotation(Transactional.class);
            assertThat(annotation).as("%s 缺少 @Transactional", name).isNotNull();
            assertThat(annotation.propagation())
                    .as("%s 必须用 REQUIRES_NEW：写入否则会被调用方回滚，读取否则看不到刚提交的用量", name)
                    .isEqualTo(Propagation.REQUIRES_NEW);
        }
    }
}
