package com.rag.studyhelper.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.studyhelper.utils.Results;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 首期应用层认证：校验 {@code X-API-Key}（或配置的头名称）。
 * {@code /api/health} 与静态资源放行，便于探活与 UI 演示。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private ApiKeyProperties apiKeyProperties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!apiKeyProperties.isConfigured()) {
            return true;
        }
        String path = request.getRequestURI();
        if (path == null) {
            return false;
        }
        if ("/api/health".equals(path)) {
            return true;
        }
        if (!path.startsWith("/api/")) {
            return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String provided = request.getHeader(apiKeyProperties.getHeader());
        if (apiKeyProperties.getValue().equals(provided)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Results<Void> body = Results.failed("401", "Missing or invalid API Key");
        response.getWriter().write(MAPPER.writeValueAsString(body));
    }
}
