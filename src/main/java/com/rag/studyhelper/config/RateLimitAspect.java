package com.rag.studyhelper.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.studyhelper.service.RateLimitService;
import com.rag.studyhelper.utils.Results;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.redisson.api.RateIntervalUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    public RateLimitAspect(RateLimitService rateLimitService, ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
    }

    @Value("${app.rate-limit.ip-rate:20}")
    private int ipRate;

    @Value("${app.rate-limit.daily-max:10000}")
    private int dailyMax;

    @Value("${app.rate-limit.trust-forwarded-for:false}")
    private boolean trustForwardedFor;

    /**
     * 聊天接口限流
     * 定制化 支持热更新
     */
    @Around("@annotation(ipRateLimit)")
    public Object chatAround(ProceedingJoinPoint pjp, IpRateLimit ipRateLimit) throws Throwable {
        // 从当前请求上下文获取 request / response
        ServletRequestAttributes attrs = (ServletRequestAttributes)
                RequestContextHolder.currentRequestAttributes();
        HttpServletRequest request = attrs.getRequest();
        HttpServletResponse response = attrs.getResponse();

        String key = ipRateLimit.value();

        try {
            String clientIp = getClientIp(request);
            if (!rateLimitService.tryAcquire("ip:" + key + ":" + clientIp, ipRate)) {
                writeResponse(response, HttpStatus.TOO_MANY_REQUESTS.value(),
                        "429", "请求过于频繁，请稍后再试");
                return null;
            }
            if (dailyMax > 0 && !rateLimitService.tryDaily(key, dailyMax)) {
                writeResponse(response, HttpStatus.TOO_MANY_REQUESTS.value(),
                        "429", "今日调用次数已达上限");
                return null;
            }
        } catch (RuntimeException unavailable) {
            writeResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "503", "限流依赖暂时不可用");
            return null;
        }

        // 通过限流，执行业务方法
        return pjp.proceed();
    }


    /**
     * 通用限流
     */
    @Around("@annotation(rateLimit)")
    public Object commonAround(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        // 从当前请求上下文获取 response
        ServletRequestAttributes attrs = (ServletRequestAttributes)
                RequestContextHolder.currentRequestAttributes();
        HttpServletResponse response = attrs.getResponse();

        String key = rateLimit.key();
        long count = rateLimit.count();
        long supplementTime = rateLimit.supplementTime();
        RateIntervalUnit rateIntervalUnit = rateLimit.supplementTimeUnit();
        long timeOutOfHours = rateLimit.timeOutOfHours();
        int dailyMaximumCount = rateLimit.dailyMaximumCount();

        try {
            if (dailyMaximumCount != 0) {
                // 全局每日限流
                if (!rateLimitService.tryDaily(key, dailyMaximumCount)) {
                    writeResponse(response, HttpStatus.TOO_MANY_REQUESTS.value(),
                            "429", "今日调用次数已达上限");
                    return null;
                }
            }

            // 令牌桶限流
            if (!rateLimitService.tryAcquire(key, count, supplementTime,
                    rateIntervalUnit, timeOutOfHours)) {
                writeResponse(response, HttpStatus.TOO_MANY_REQUESTS.value(),
                        "429", "请求过于频繁，请稍后再试");
                return null;
            }
        } catch (RuntimeException unavailable) {
            writeResponse(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "503", "限流依赖暂时不可用");
            return null;
        }

        // 通过限流，执行业务方法
        return pjp.proceed();
    }

    // 获取IP
    private String getClientIp(HttpServletRequest request) {
        String xff = trustForwardedFor ? request.getHeader("X-Forwarded-For") : null;
        if (xff != null && !xff.isBlank() && !"unknown".equalsIgnoreCase(xff)) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    // 响应限流报错信息
    private void writeResponse(HttpServletResponse response, int status,
                               String code, String msg) throws Exception {
        response.setStatus(status);
        response.setHeader("Retry-After", "60");
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        Results<Void> result = Results.failed(code, msg);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
