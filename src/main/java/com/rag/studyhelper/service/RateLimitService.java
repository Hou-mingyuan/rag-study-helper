package com.rag.studyhelper.service;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redisson 分布式令牌桶的限流服务。
 * <p>
 * 支持两级限流：
 * <ul>
 *   <li><b>令牌桶</b>：Redisson RRateLimiter，分布式原子操作，支持多实例共享</li>
 *   <li><b>每日计数器</b>：Spring RedisTemplate INCR，每日零点自动重置</li>
 * </ul>
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;

    public RateLimitService(RedissonClient redissonClient, StringRedisTemplate redisTemplate) {
        this.redissonClient = redissonClient;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 分布式令牌桶限流。
     * <p>
     * 基于 Redisson RRateLimiter，利用 Lua 脚本实现连续补充：
     * <pre>
     * 桶剩余 = min(rate, 上次剩余 + 经过时间 × rate / interval)
     * </pre>
     * Redisson 的 trySetRate 中 {@code rate} 同时决定桶容量和补充速率，
     * 二者相等，适合单纯控制速率无需额外突发空间的场景。
     * <p>
     * 补充是懒计算的——每次请求时根据时间差换算应得令牌，并非定时补充。
     * 使用 {@link RateType#OVERALL}，所有实例共享同一桶。
     * 设置 24h TTL 避免 key 永久残留 Redis。
     * <p>
     * 将 {@code permitsPerMin} 编码进 key，配置变更时自动使用新 key，
     * 旧 key 随 TTL 过期，无需额外处理即可实现热更新。
     *
     * @param key           限流 key
     * @param permitsPerMin 每分钟许可数（同时作为桶容量上限）
     * @return true 允许通过，false 触发限流
     */
    public boolean tryAcquire(String key, int permitsPerMin) {
        // 将配置值编码进 key，变更配置即自动切换限流器，旧 key 随 TTL 过期
        RRateLimiter limiter = redissonClient.getRateLimiter("rl:" + key + ":" + permitsPerMin);
        limiter.trySetRate(RateType.OVERALL, permitsPerMin, 1, RateIntervalUnit.MINUTES);
        // 24h TTL，避免 key 永久残留 Redis
        limiter.expire(Duration.ofHours(24));
        return limiter.tryAcquire();
    }

    /**
     * 全局每日调用计数限流。
     * <p>
     * 使用 INCR + EXPIRE 实现，每日零点自动重置。
     *
     * @param keyPrefix key 前缀（如 "chat"）
     * @param maxCalls  每日最大调用次数
     * @return true 允许通过，false 超限
     */
    public boolean tryDaily(String keyPrefix, int maxCalls) {
        String key = "rl:daily:" + keyPrefix + ":" + LocalDate.now();
        Long count = redisTemplate.opsForValue().increment(key);
        // 首次创建时设置过期时间，28 小时后自动删除
        if (count != null && count == 1L) {
            redisTemplate.expire(key, 28, TimeUnit.HOURS);
        }
        if (count != null && count > maxCalls) {
            log.warn("每日调用超限: key={}, count={}, max={}", keyPrefix, count, maxCalls);
            return false;
        }
        return true;
    }
}
