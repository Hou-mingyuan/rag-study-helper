package com.rag.studyhelper.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.studyhelper.model.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "redis.it.host", matches = ".+")
class RedisCoordinationIT {

    private LettuceConnectionFactory connectionOne;
    private LettuceConnectionFactory connectionTwo;
    private StringRedisTemplate redisOne;
    private StringRedisTemplate redisTwo;
    private RedissonClient redissonOne;
    private RedissonClient redissonTwo;
    private String runId;

    @BeforeEach
    void setUp() {
        String host = System.getProperty("redis.it.host");
        int port = Integer.parseInt(System.getProperty("redis.it.port", "16379"));
        String password = System.getProperty("redis.it.password", "");
        int database = Integer.parseInt(System.getProperty("redis.it.database", "3"));
        connectionOne = connection(host, port, password, database);
        connectionTwo = connection(host, port, password, database);
        redisOne = template(connectionOne);
        redisTwo = template(connectionTwo);
        redissonOne = redisson(host, port, password, database);
        redissonTwo = redisson(host, port, password, database);
        runId = UUID.randomUUID().toString().replace("-", "");
    }

    @AfterEach
    void tearDown() {
        if (redissonOne != null) {
            redissonOne.getKeys().deleteByPattern("*" + runId + "*");
        }
        if (redisOne != null) {
            redisOne.delete("rag:space:1:session:" + runId + ":messages");
            redisOne.delete("rag:space:2:session:" + runId + ":messages");
        }
        if (redissonOne != null) {
            redissonOne.shutdown();
        }
        if (redissonTwo != null) {
            redissonTwo.shutdown();
        }
        if (connectionOne != null) {
            connectionOne.destroy();
        }
        if (connectionTwo != null) {
            connectionTwo.destroy();
        }
    }

    @Test
    void twoApplicationInstancesAppendAtomicConversationTurnsWithoutLostPairs() throws Exception {
        RedisConversationStore first = new RedisConversationStore(redisOne, new ObjectMapper());
        RedisConversationStore second = new RedisConversationStore(redisTwo, new ObjectMapper());
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 30; index++) {
                int turn = index;
                RedisConversationStore store = index % 2 == 0 ? first : second;
                futures.add(pool.submit(() -> {
                    start.await();
                    store.addTurn(1L, runId, "u-" + turn, "a-" + turn);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }

        List<ChatMessage> history = first.getHistory(1L, runId);
        assertEquals(20, history.size());
        for (int index = 0; index < history.size(); index += 2) {
            ChatMessage user = history.get(index);
            ChatMessage assistant = history.get(index + 1);
            assertEquals("user", user.getRole());
            assertEquals("assistant", assistant.getRole());
            assertEquals(user.getContent().substring(2), assistant.getContent().substring(2));
        }
        Long ttl = redisOne.getExpire("rag:space:1:session:" + runId + ":messages");
        assertTrue(ttl != null && ttl > Duration.ofMinutes(50).toSeconds());

        second.addTurn(2L, runId, "u-space-2", "a-space-2");
        assertEquals(2, first.getHistory(2L, runId).size());
        assertEquals(20, first.getHistory(1L, runId).size());
    }

    @Test
    void twoApplicationInstancesShareOneRateLimitBudget() throws Exception {
        RateLimitService first = new RateLimitService(redissonOne, redisOne);
        RateLimitService second = new RateLimitService(redissonTwo, redisTwo);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        String key = "multi-instance-" + runId;
        try {
            for (int index = 0; index < 40; index++) {
                RateLimitService service = index % 2 == 0 ? first : second;
                futures.add(pool.submit(() -> {
                    start.await();
                    if (service.tryAcquire(key, 10, 1, RateIntervalUnit.MINUTES, 1)) {
                        accepted.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(10, accepted.get());
        assertTrue(first.tryDaily(key, 3));
        assertTrue(second.tryDaily(key, 3));
        assertTrue(first.tryDaily(key, 3));
        assertEquals(false, second.tryDaily(key, 3));
    }

    private LettuceConnectionFactory connection(String host, int port, String password, int database) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(host, port);
        standalone.setDatabase(database);
        if (!password.isBlank()) {
            standalone.setPassword(RedisPassword.of(password));
        }
        LettuceConnectionFactory connection = new LettuceConnectionFactory(standalone);
        connection.afterPropertiesSet();
        return connection;
    }

    private StringRedisTemplate template(LettuceConnectionFactory connection) {
        StringRedisTemplate template = new StringRedisTemplate(connection);
        template.afterPropertiesSet();
        return template;
    }

    private RedissonClient redisson(String host, int port, String password, int database) {
        Config config = new Config();
        var server = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database);
        if (!password.isBlank()) {
            server.setPassword(password);
        }
        return Redisson.create(config);
    }
}
