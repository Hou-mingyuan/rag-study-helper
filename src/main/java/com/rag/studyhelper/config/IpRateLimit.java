package com.rag.studyhelper.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 定制化 ip 限流注解 支持热更新
 * <p>
 * aop 使用在注册了 spring bean 的类方法上，由 {@link RateLimitAspect} 拦截并执行限流。
 * 限流参数在 application.yml 中统一配置（app.rate-limit.*）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IpRateLimit {
    /**
     * 限流 key
     */
    String value();
}
