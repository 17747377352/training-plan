package com.trainingplan.platform.controller;

import com.trainingplan.platform.config.JwtConfig;
import com.trainingplan.platform.config.SecurityConfig;
import com.trainingplan.platform.security.CollectorTokenFilter;
import com.trainingplan.platform.security.RestAccessDeniedHandler;
import com.trainingplan.platform.security.RestAuthenticationEntryPoint;
import com.trainingplan.platform.service.GarminAccountService;
import com.trainingplan.platform.service.GarminPairCodeService;
import com.trainingplan.platform.service.SyncService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 桌面助手绑定通道的放行边界。
 *
 * <p>为了让助手能回传令牌，{@code /api/garmin/accounts/pair} 必须免登录。这是本项目里
 * 除注册登录之外唯一一条匿名可写的业务接口，因此它的边界要用测试钉死：</p>
 *
 * <ul>
 *   <li>只有 POST + 这一个精确路径被放行；</li>
 *   <li>其余 {@code /api/garmin/accounts/**}（包括签发配对码本身）一律仍需登录。
 *       一旦有人图省事把它写成 {@code /api/garmin/accounts/**}，第二组断言会立刻失败 ——
 *       那等于任何人都能列出、导入、删除别人的 Garmin 账号。</li>
 * </ul>
 *
 * @author gongxuesong
 * @date 2026-09-22
 */
@WebMvcTest(controllers = GarminAccountController.class,
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
class GarminPairSecurityTest {

    private static final String PAIR_BODY = """
            {"code":"ABCD2345","email":"rider@example.com",
             "tokenJson":"{\\"di_token\\":\\"x\\"}","region":"GLOBAL"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GarminAccountService garminAccountService;

    @MockitoBean
    private GarminPairCodeService pairCodeService;

    @MockitoBean
    private SyncService syncService;

    @Test
    void pairEndpointAcceptsAnonymousHelperRequest() throws Exception {
        mockMvc.perform(post("/api/garmin/accounts/pair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAIR_BODY))
                .andExpect(status().isOk());

        verify(garminAccountService).bindByPairCode(any());
    }

    @Test
    void pairEndpointStillValidatesItsBody() throws Exception {
        mockMvc.perform(post("/api/garmin/accounts/pair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"email\":\"not-an-email\",\"tokenJson\":\"\",\"region\":\"MARS\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value(40000));
    }

    @Test
    void everyOtherAccountEndpointStillRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/garmin/accounts")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/garmin/accounts/pair-code")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/garmin/accounts/import-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"tokenJson\":\"{}\",\"region\":\"GLOBAL\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/garmin/accounts/connect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"x\",\"region\":\"GLOBAL\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/garmin/accounts/9/auto-sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"syncEnabled\":0}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/garmin/accounts/9")).andExpect(status().isUnauthorized());
    }

    @Test
    void pairCodeIsIssuedForTheJwtUserOnly() throws Exception {
        mockMvc.perform(post("/api/garmin/accounts/pair-code")
                        .with(authentication(userAuthentication("7"))))
                .andExpect(status().isOk());

        verify(pairCodeService).issue(7L);
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
