package com.rag.demo.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限流注解。
 * <p>
 * 标记在 Controller 方法上，由 {@link RateLimitAspect} 拦截并执行限流。
 * 限流参数在 application.yml 中统一配置（app.rate-limit.*）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
}
