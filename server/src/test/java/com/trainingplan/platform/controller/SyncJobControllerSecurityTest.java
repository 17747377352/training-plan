package com.trainingplan.platform.controller;

import com.trainingplan.platform.common.api.PageResult;
import com.trainingplan.platform.config.JwtConfig;
import com.trainingplan.platform.config.SecurityConfig;
import com.trainingplan.platform.dto.sync.SyncJobQuery;
import com.trainingplan.platform.dto.sync.SyncOverviewDto;
import com.trainingplan.platform.security.CollectorTokenFilter;
import com.trainingplan.platform.security.RestAccessDeniedHandler;
import com.trainingplan.platform.security.RestAuthenticationEntryPoint;
import com.trainingplan.platform.service.SyncService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 同步任务看板接口的登录与用户边界测试。 */
@WebMvcTest(controllers = SyncJobController.class,
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
class SyncJobControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SyncService syncService;

    @Test
    void shouldRejectAnonymousListRequest() throws Exception {
        mockMvc.perform(get("/api/sync/jobs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void shouldRejectAnonymousOverviewRequest() throws Exception {
        mockMvc.perform(get("/api/sync/jobs/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void shouldRejectAnonymousRetryRequest() throws Exception {
        mockMvc.perform(post("/api/sync/jobs/9/retry"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void shouldPassFiltersAndJwtUserToJobQuery() throws Exception {
        when(syncService.listJobs(eq(7L), any()))
                .thenReturn(new PageResult<>(0L, 2L, 5L, List.of()));

        mockMvc.perform(get("/api/sync/jobs")
                        .with(authentication(userAuthentication("7")))
                        .param("page", "2")
                        .param("size", "5")
                        .param("jobStatus", "FAILED")
                        .param("garminAccountId", "16"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        var queryCaptor = org.mockito.ArgumentCaptor.forClass(SyncJobQuery.class);
        verify(syncService).listJobs(eq(7L), queryCaptor.capture());
        assertThat(queryCaptor.getValue().getPage()).isEqualTo(2);
        assertThat(queryCaptor.getValue().getSize()).isEqualTo(5);
        assertThat(queryCaptor.getValue().getJobStatus()).isEqualTo("FAILED");
        assertThat(queryCaptor.getValue().getGarminAccountId()).isEqualTo(16L);
    }

    @Test
    void shouldRejectUnknownJobStatusFilter() throws Exception {
        mockMvc.perform(get("/api/sync/jobs")
                        .with(authentication(userAuthentication("7")))
                        .param("jobStatus", "WHATEVER"))
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void shouldQueryOverviewForJwtUser() throws Exception {
        when(syncService.overview(7L))
                .thenReturn(new SyncOverviewDto(null, null, 0L, 0L, false, List.of()));

        mockMvc.perform(get("/api/sync/jobs/overview")
                        .with(authentication(userAuthentication("7"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasGarminAccount").value(false));

        verify(syncService).overview(7L);
    }

    @Test
    void shouldRetryWithJwtUserNotRequestParam() throws Exception {
        when(syncService.retryJob(7L, 9L)).thenReturn(20L);

        mockMvc.perform(post("/api/sync/jobs/9/retry")
                        .with(authentication(userAuthentication("7"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(20));

        verify(syncService).retryJob(7L, 9L);
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
