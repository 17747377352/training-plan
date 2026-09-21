package com.trainingplan.platform.controller;

import com.trainingplan.platform.common.api.PageResult;
import com.trainingplan.platform.config.JwtConfig;
import com.trainingplan.platform.config.SecurityConfig;
import com.trainingplan.platform.dto.activity.ActivityQuery;
import com.trainingplan.platform.security.CollectorTokenFilter;
import com.trainingplan.platform.security.RestAccessDeniedHandler;
import com.trainingplan.platform.security.RestAuthenticationEntryPoint;
import com.trainingplan.platform.service.ActivityService;
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

/** 活动查询接口的登录与用户边界测试。 */
@WebMvcTest(controllers = ActivityController.class,
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
class ActivityControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ActivityService activityService;

    @Test
    void shouldRejectAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/activities"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void shouldPassJwtUserAndFiltersToService() throws Exception {
        when(activityService.listActivities(eq(7L), any()))
                .thenReturn(new PageResult<>(0L, 2L, 10L, List.of()));

        mockMvc.perform(get("/api/activities")
                        .with(authentication(userAuthentication("7")))
                        .param("page", "2")
                        .param("size", "10")
                        .param("startDate", "2026-09-01")
                        .param("endDate", "2026-09-21")
                        .param("typeKey", "road_biking")
                        .param("keyword", "爬坡"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.page").value(2));

        var queryCaptor = org.mockito.ArgumentCaptor.forClass(ActivityQuery.class);
        verify(activityService).listActivities(eq(7L), queryCaptor.capture());
        ActivityQuery query = queryCaptor.getValue();
        assertThat(query.currentPage()).isEqualTo(2);
        assertThat(query.pageSize()).isEqualTo(10);
        assertThat(query.getStartDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(query.getEndDate()).isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(query.getTypeKey()).isEqualTo("road_biking");
        assertThat(query.getKeyword()).isEqualTo("爬坡");
    }

    @Test
    void shouldListTypesForJwtUser() throws Exception {
        when(activityService.listActivityTypes(7L))
                .thenReturn(List.of("road_biking"));

        mockMvc.perform(get("/api/activities/types")
                        .with(authentication(userAuthentication("7"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("road_biking"));
    }

    @Test
    void shouldGetActivityDetailForJwtUser() throws Exception {
        mockMvc.perform(get("/api/activities/101")
                        .with(authentication(userAuthentication("7"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(activityService).getActivity(7L, 101L);
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
