package com.trainingplan.platform.controller;

import com.trainingplan.platform.config.JwtConfig;
import com.trainingplan.platform.config.SecurityConfig;
import com.trainingplan.platform.security.CollectorTokenFilter;
import com.trainingplan.platform.security.RestAccessDeniedHandler;
import com.trainingplan.platform.security.RestAuthenticationEntryPoint;
import com.trainingplan.platform.service.BrowserUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BrowserUploadController.class,
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
class BrowserUploadSecurityTest {

    @Autowired MockMvc mvc;
    @MockitoBean BrowserUploadService service;

    @Test
    void pairAndUploadAreAvailableToDesktopButRevokeNeedsPlatformLogin() throws Exception {
        mvc.perform(post("/api/garmin/browser-upload/pair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ABCD2345\",\"email\":\"rider@example.com\",\"region\":\"GLOBAL\"}"))
                .andExpect(status().isOk());
        verify(service).pair(any());

        mvc.perform(post("/api/garmin/browser-upload/ingest")
                        .header("X-Garmin-Upload-Token", "sample-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-09-23\",\"endDate\":\"2026-09-23\",\"data\":{}}"))
                .andExpect(status().isOk());
        verify(service).ingest(eq("sample-token"), any());

        mvc.perform(delete("/api/garmin/browser-upload/credentials/11"))
                .andExpect(status().isUnauthorized());
    }
}
