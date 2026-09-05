package com.rag.studyhelper.controller;

import com.rag.studyhelper.config.ApiKeyProperties;
import com.rag.studyhelper.config.RagProviderResolver;
import com.rag.studyhelper.vector.VectorIndexManager;
import com.rag.studyhelper.vector.VectorRebuildReport;
import com.rag.studyhelper.vector.VectorStoreGateway;
import com.rag.studyhelper.vector.VectorStoreStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    private final ThreadPoolTaskExecutor executor = executor();

    @AfterEach
    void shutDownExecutor() {
        executor.shutdown();
    }

    @Test
    void readinessRunsIndependentDependencyProbesInParallel() {
        CountDownLatch started = new CountDownLatch(4);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RedisConnectionFactory redisFactory = mock(RedisConnectionFactory.class);
        RedisConnection redis = mock(RedisConnection.class);
        VectorStoreGateway vectorStore = mock(VectorStoreGateway.class);
        VectorIndexManager vectorIndex = mock(VectorIndexManager.class);

        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class)))
                .thenAnswer(invocation -> afterAllStarted(started, 1));
        when(redisFactory.getConnection()).thenReturn(redis);
        when(redis.ping()).thenAnswer(invocation -> afterAllStarted(started, "PONG"));
        when(vectorStore.status()).thenAnswer(invocation ->
                afterAllStarted(started, new VectorStoreStatus("chroma", "test", true, "reachable")));
        when(vectorIndex.status()).thenAnswer(invocation ->
                afterAllStarted(started, new VectorRebuildReport(
                        "chroma", "test", "test-model", 1536, 5, 5, "READY")));

        HealthController controller = new HealthController(
                mock(RagProviderResolver.class),
                mock(ApiKeyProperties.class),
                jdbc,
                redisFactory,
                vectorStore,
                vectorIndex,
                executor);

        var response = controller.readiness();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(0, started.getCount());
    }

    @Test
    void readinessReusesFreshProbeSnapshot() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RedisConnectionFactory redisFactory = mock(RedisConnectionFactory.class);
        RedisConnection redis = mock(RedisConnection.class);
        VectorStoreGateway vectorStore = mock(VectorStoreGateway.class);
        VectorIndexManager vectorIndex = mock(VectorIndexManager.class);
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        when(redisFactory.getConnection()).thenReturn(redis);
        when(redis.ping()).thenReturn("PONG");
        when(vectorStore.status()).thenReturn(new VectorStoreStatus("chroma", "test", true, "reachable"));
        when(vectorIndex.status()).thenReturn(new VectorRebuildReport(
                "chroma", "test", "test-model", 1536, 5, 5, "READY"));

        HealthController controller = new HealthController(
                mock(RagProviderResolver.class),
                mock(ApiKeyProperties.class),
                jdbc,
                redisFactory,
                vectorStore,
                vectorIndex,
                executor);

        assertEquals(HttpStatus.OK, controller.readiness().getStatusCode());
        assertEquals(HttpStatus.OK, controller.readiness().getStatusCode());

        verify(jdbc, times(1)).queryForObject("SELECT 1", Integer.class);
        verify(redisFactory, times(1)).getConnection();
        verify(vectorStore, times(1)).status();
        verify(vectorIndex, times(1)).status();
    }

    @Test
    void staleReadinessReturnsImmediatelyWhileOneRefreshRunsInBackground() throws Exception {
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        CountDownLatch refreshReturned = new CountDownLatch(1);
        AtomicInteger storeCalls = new AtomicInteger();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RedisConnectionFactory redisFactory = mock(RedisConnectionFactory.class);
        RedisConnection redis = mock(RedisConnection.class);
        VectorStoreGateway vectorStore = mock(VectorStoreGateway.class);
        VectorIndexManager vectorIndex = mock(VectorIndexManager.class);
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        when(redisFactory.getConnection()).thenReturn(redis);
        when(redis.ping()).thenReturn("PONG");
        when(vectorStore.status()).thenAnswer(invocation -> {
            if (storeCalls.incrementAndGet() > 1) {
                refreshStarted.countDown();
                releaseRefresh.await(3, TimeUnit.SECONDS);
                refreshReturned.countDown();
            }
            return new VectorStoreStatus("chroma", "test", true, "reachable");
        });
        when(vectorIndex.status()).thenReturn(new VectorRebuildReport(
                "chroma", "test", "test-model", 1536, 5, 5, "READY"));

        HealthController controller = new HealthController(
                mock(RagProviderResolver.class),
                mock(ApiKeyProperties.class),
                jdbc,
                redisFactory,
                vectorStore,
                vectorIndex,
                executor);
        assertEquals(HttpStatus.OK, controller.readiness().getStatusCode());
        ReflectionTestUtils.setField(controller, "readinessCacheMillis", 0L);

        long startedAt = System.nanoTime();
        var staleResponse = controller.readiness();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        try {
            assertEquals(HttpStatus.OK, staleResponse.getStatusCode());
            assertTrue(elapsedMillis < 500,
                    "stale readiness waited for the background refresh: " + elapsedMillis + "ms");
            assertTrue(refreshStarted.await(1, TimeUnit.SECONDS),
                    "background readiness refresh did not start");
        } finally {
            releaseRefresh.countDown();
        }
        assertTrue(refreshReturned.await(1, TimeUnit.SECONDS),
                "background readiness refresh did not finish");
        verify(vectorStore, times(2)).status();
    }

    @Test
    void readinessWaitsForRefreshAfterMaximumStaleAge() throws Exception {
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        AtomicInteger storeCalls = new AtomicInteger();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RedisConnectionFactory redisFactory = mock(RedisConnectionFactory.class);
        RedisConnection redis = mock(RedisConnection.class);
        VectorStoreGateway vectorStore = mock(VectorStoreGateway.class);
        VectorIndexManager vectorIndex = mock(VectorIndexManager.class);
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        when(redisFactory.getConnection()).thenReturn(redis);
        when(redis.ping()).thenReturn("PONG");
        when(vectorStore.status()).thenAnswer(invocation -> {
            if (storeCalls.incrementAndGet() > 1) {
                refreshStarted.countDown();
                releaseRefresh.await(3, TimeUnit.SECONDS);
            }
            return new VectorStoreStatus("chroma", "test", true, "reachable");
        });
        when(vectorIndex.status()).thenReturn(new VectorRebuildReport(
                "chroma", "test", "test-model", 1536, 5, 5, "READY"));

        HealthController controller = new HealthController(
                mock(RagProviderResolver.class),
                mock(ApiKeyProperties.class),
                jdbc,
                redisFactory,
                vectorStore,
                vectorIndex,
                executor);
        assertEquals(HttpStatus.OK, controller.readiness().getStatusCode());
        ReflectionTestUtils.setField(controller, "readinessCacheMillis", 0L);
        ReflectionTestUtils.setField(controller, "readinessMaxStaleMillis", 0L);

        CompletableFuture<Integer> response = CompletableFuture.supplyAsync(
                () -> controller.readiness().getStatusCode().value());
        assertTrue(refreshStarted.await(1, TimeUnit.SECONDS),
                "maximum-stale refresh did not start");
        assertFalse(response.isDone(), "maximum-stale readiness did not wait for a fresh probe");
        releaseRefresh.countDown();

        assertEquals(HttpStatus.OK.value(), response.get(1, TimeUnit.SECONDS));
        verify(vectorStore, times(2)).status();
    }

    private static <T> T afterAllStarted(CountDownLatch started, T value) throws InterruptedException {
        started.countDown();
        assertTrue(started.await(2, TimeUnit.SECONDS), "readiness probes did not start in parallel");
        return value;
    }

    private static ThreadPoolTaskExecutor executor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(4);
        executor.initialize();
        return executor;
    }
}
