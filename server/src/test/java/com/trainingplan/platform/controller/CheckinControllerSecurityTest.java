package com.trainingplan.platform.controller;

import com.trainingplan.platform.config.JwtConfig;
import com.trainingplan.platform.config.SecurityConfig;
import com.trainingplan.platform.dto.checkin.CheckinRequest;
import com.trainingplan.platform.security.CollectorTokenFilter;
import com.trainingplan.platform.security.RestAccessDeniedHandler;
import com.trainingplan.platform.security.RestAuthenticationEntryPoint;
import com.trainingplan.platform.service.CheckinService;
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

/** 手工打卡接口的登录与用户边界测试。 */
@WebMvcTest(controllers = CheckinController.class,
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
class CheckinControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckinService checkinService;

    @Test
    void shouldRejectAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/checkins"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        mockMvc.perform(put("/api/checkins/2026-09-21")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weightKg\":70}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/checkins/2026-09-21"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldSaveCheckinForJwtUserNotRequestParam() throws Exception {
        mockMvc.perform(put("/api/checkins/2026-09-21")
                        .with(authentication(userAuthentication("7")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weightKg\":71.5,\"rpe\":6,\"note\":\"状态一般\"}"))
                .andExpect(status().isOk());

        var dateCaptor = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(CheckinRequest.class);
        verify(checkinService).saveCheckin(eq(7L), dateCaptor.capture(), requestCaptor.capture());
        assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(requestCaptor.getValue().getRpe()).isEqualTo(6);
    }

    @Test
    void shouldRejectOutOfRangeRpe() throws Exception {
        mockMvc.perform(put("/api/checkins/2026-09-21")
                        .with(authentication(userAuthentication("7")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rpe\":99}"))
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void shouldQueryCheckinsForJwtUser() throws Exception {
        mockMvc.perform(get("/api/checkins")
                        .with(authentication(userAuthentication("7")))
                        .param("startDate", "2026-09-01")
                        .param("endDate", "2026-09-21"))
                .andExpect(status().isOk());

        verify(checkinService).listCheckins(eq(7L), any());
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
