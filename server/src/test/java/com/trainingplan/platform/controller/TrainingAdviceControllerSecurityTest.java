package com.trainingplan.platform.controller;

import com.trainingplan.platform.config.JwtConfig;
import com.trainingplan.platform.config.SecurityConfig;
import com.trainingplan.platform.service.TrainingPlanService;
import com.trainingplan.platform.security.CollectorTokenFilter;
import com.trainingplan.platform.security.RestAccessDeniedHandler;
import com.trainingplan.platform.security.RestAuthenticationEntryPoint;
import com.trainingplan.platform.service.AiUsageService;
import com.trainingplan.platform.service.TrainingAdviceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 训练建议接口必须从 JWT 取用户，并拒绝匿名与无效日期。 */
@WebMvcTest(controllers = {TrainingAdviceController.class, TrainingPlanController.class},
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = CollectorTokenFilter.class))
@Import({SecurityConfig.class, JwtConfig.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
@ImportAutoConfiguration(AopAutoConfiguration.class)
@TestPropertySource(properties = {
        "app.security.jwt-secret=unit-test-jwt-secret-key-with-32-plus-characters",
        "app.security.issuer=training-plan-server",
        "app.security.access-token-ttl=15m",
        "app.security.refresh-token-ttl=30d",
        "app.security.token-cipher-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
class TrainingAdviceControllerSecurityTest {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private TrainingAdviceService adviceService;
    @MockitoBean private TrainingPlanService planService;
    @MockitoBean private AiUsageService aiUsageService;

    @Test
    void anonymousCannotReadAdvice() throws Exception {
        mockMvc.perform(get("/api/training-advice")).andExpect(status().isUnauthorized());
    }

    @Test
    void usesJwtUserAndParsedDateIgnoringUserIdInQuery() throws Exception {
        mockMvc.perform(get("/api/training-advice?date=2026-09-20&userId=999&garminAccountId=999")
                .with(authentication(userAuthentication("7")))).andExpect(status().isOk());
        verify(adviceService).getAdvice(7L, LocalDate.of(2026, 9, 20));
    }

    @Test
    void rejectsInvalidDateAndJwtSubject() throws Exception {
        mockMvc.perform(get("/api/training-advice?date=2026-02-30")
                .with(authentication(userAuthentication("7"))))
                .andExpect(jsonPath("$.code").value(40000));
        mockMvc.perform(get("/api/training-advice")
                .with(authentication(userAuthentication("not-a-user"))))
                .andExpect(jsonPath("$.code").value(40100));
        org.mockito.Mockito.verifyNoInteractions(adviceService);
    }

    @Test
    void generationRequiresLoginAndUsesJwtIdentity() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/training-plans/generate"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/training-plans/generate?userId=999")
                .with(authentication(userAuthentication("7")))).andExpect(status().isOk());
        verify(planService).generate(7L, false);
    }

    private JwtAuthenticationToken userAuthentication(String subject) {
        Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "HS256")
                .subject(subject).claim("token_type", "access").build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
