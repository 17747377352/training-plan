package com.trainingplan.platform.controller;

import com.trainingplan.platform.config.JwtConfig;
import com.trainingplan.platform.config.SecurityConfig;
import com.trainingplan.platform.dto.health.TrendQuery;
import com.trainingplan.platform.security.CollectorTokenFilter;
import com.trainingplan.platform.security.RestAccessDeniedHandler;
import com.trainingplan.platform.security.RestAuthenticationEntryPoint;
import com.trainingplan.platform.service.HealthTrendService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 健康趋势接口的登录与用户边界测试。 */
@WebMvcTest(controllers = HealthTrendController.class,
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
class HealthTrendControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthTrendService healthTrendService;

    @Test
    void shouldRejectAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/health/daily"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void shouldPassDateRangeAndJwtUserToDailyHealthService() throws Exception {
        when(healthTrendService.listDailyHealth(eq(7L), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/health/daily")
                        .with(authentication(userAuthentication("7")))
                        .param("startDate", "2026-09-01")
                        .param("endDate", "2026-09-21"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        var queryCaptor = org.mockito.ArgumentCaptor.forClass(TrendQuery.class);
        verify(healthTrendService).listDailyHealth(eq(7L), queryCaptor.capture());
        assertThat(queryCaptor.getValue().getStartDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(queryCaptor.getValue().getEndDate()).isEqualTo(LocalDate.of(2026, 9, 21));
    }

    @Test
    void shouldQueryHrvForJwtUser() throws Exception {
        when(healthTrendService.listHrv(eq(7L), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/health/hrv")
                        .with(authentication(userAuthentication("7"))))
                .andExpect(status().isOk());

        verify(healthTrendService).listHrv(eq(7L), any());
    }

    @Test
    void shouldQuerySleepForJwtUser() throws Exception {
        when(healthTrendService.listSleep(eq(7L), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/sleep")
                        .with(authentication(userAuthentication("7"))))
                .andExpect(status().isOk());

        verify(healthTrendService).listSleep(eq(7L), any());
    }

    private JwtAuthenticationToken userAuthentication(String subject) {
        Jwt jwt = Jwt.withTokenValue("unit-test-token")
                .header("alg", "HS256")
                .subject(subject)
                .claim("roles", List.of("USER"))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
