package com.rag.studyhelper.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.studyhelper.service.RateLimitService;
import com.rag.studyhelper.utils.Results;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 限流切面。
 * <p>
 * 拦截所有 {@link RateLimit} 注解的方法，执行两级限流：
 * <ol>
 *   <li>全局每日计数器</li>
 *   <li>IP 令牌桶</li>
 * </ol>
 */
@Aspect
@Component
public class RateLimitAspect {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private RateLimitService rateLimitService;

    @Value("${app.rate-limit.ip-rate:20}")
    private int ipRate;

    @Value("${app.rate-limit.daily-max:10000}")
    private int dailyMax;

    @Around("@annotation(com.rag.studyhelper.config.RateLimit)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        // 从当前请求上下文获取 request / response
        ServletRequestAttributes attrs = (ServletRequestAttributes)
                RequestContextHolder.currentRequestAttributes();
        HttpServletRequest request = attrs.getRequest();
        HttpServletResponse response = attrs.getResponse();

        // 1. 全局每日限流
        if (!rateLimitService.tryDaily("chat", dailyMax)) {
            writeRateLimitResponse(response, "今日调用次数已达上限");
            return null;
        }

        // 2. IP 令牌桶限流
        String clientIp = getClientIp(request);
        if (!rateLimitService.tryAcquire("ip:" + clientIp, ipRate)) {
            writeRateLimitResponse(response, "请求过于频繁，请稍后再试");
            return null;
        }

        // 通过限流，执行业务方法
        return pjp.proceed();
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty() && !"unknown".equalsIgnoreCase(xff)) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void writeRateLimitResponse(HttpServletResponse response, String msg) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        Results<Void> result = Results.failed("429", msg);
        response.getWriter().write(OBJECT_MAPPER.writeValueAsString(result));
    }
}
