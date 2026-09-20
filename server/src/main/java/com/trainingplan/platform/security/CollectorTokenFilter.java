package com.trainingplan.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainingplan.platform.common.api.Result;
import com.trainingplan.platform.common.error.ErrorCode;
import com.trainingplan.platform.config.CollectorProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部采集器接口鉴权过滤器。
 *
 * <p>{@code /internal/**} 不对平台用户开放，只允许持有内部服务凭据的采集器调用。
 * 未配置凭据时一律拒绝，避免默认放行。</p>
 *
 * @author gongxuesong
 * @date 2026-09-20
 */
@Component
@RequiredArgsConstructor
public class CollectorTokenFilter extends OncePerRequestFilter {

    private static final String TOKEN_HEADER = "X-Collector-Token";
    private static final String INTERNAL_PREFIX = "/internal/";

    private final CollectorProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith(INTERNAL_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!isValid(request.getHeader(TOKEN_HEADER))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(), Result.failure(ErrorCode.UNAUTHORIZED));
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 常量时间比较服务凭据，避免按字符比较带来的时序差异。
     *
     * @param provided 请求头中的凭据
     * @return 是否有效
     */
    private boolean isValid(String provided) {
        String expected = properties.serviceToken();
        if (provided == null || expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
