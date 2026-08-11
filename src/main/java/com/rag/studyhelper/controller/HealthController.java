package com.rag.studyhelper.controller;

import com.rag.studyhelper.utils.Results;
import org.springframework.beans.factory.annotation.Value;
import com.rag.studyhelper.config.ApiKeyProperties;
import com.rag.studyhelper.config.RagProviderResolver;
import com.rag.studyhelper.vector.VectorIndexManager;
import com.rag.studyhelper.vector.VectorRebuildReport;
import com.rag.studyhelper.vector.VectorStoreGateway;
import com.rag.studyhelper.vector.VectorStoreStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 运行态健康检查接口。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @Value("${vector.store.type:in-memory}")
    private String vectorStoreType;

    @Value("${app.feishu.sync-enabled:false}")
    private boolean feishuSyncEnabled;

    @Value("${app.health.readiness-cache-millis:500}")
    private long readinessCacheMillis = 500;

    @Value("${app.health.readiness-max-stale-millis:5000}")
    private long readinessMaxStaleMillis = 5000;

    private final RagProviderResolver ragProviderResolver;
    private final ApiKeyProperties apiKeyProperties;
    private final JdbcTemplate jdbc;
    private final RedisConnectionFactory redis;
    private final VectorStoreGateway vectorStore;
    private final VectorIndexManager vectorIndex;
    private final TaskExecutor healthProbeExecutor;
    private final Object readinessLock = new Object();
    private volatile CachedReadiness cachedReadiness;
    private volatile CompletableFuture<ResponseEntity<Results<Map<String, Object>>>> readinessRefresh;

    public HealthController(RagProviderResolver ragProviderResolver,
                            ApiKeyProperties apiKeyProperties,
                            JdbcTemplate jdbc,
                            RedisConnectionFactory redis,
                            VectorStoreGateway vectorStore,
                            VectorIndexManager vectorIndex,
                            @Qualifier("healthProbeExecutor") TaskExecutor healthProbeExecutor) {
        this.ragProviderResolver = ragProviderResolver;
        this.apiKeyProperties = apiKeyProperties;
        this.jdbc = jdbc;
        this.redis = redis;
        this.vectorStore = vectorStore;
        this.vectorIndex = vectorIndex;
        this.healthProbeExecutor = healthProbeExecutor;
    }

    @GetMapping("/health")
    public Results<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", "rag-study-helper");
        status.put("ragProvider", ragProviderResolver.isMockMode() ? "mock" : "openai");
        status.put("vectorStore", vectorStoreType);
        status.put("feishuSyncEnabled", feishuSyncEnabled);
        status.put("apiKeyAuthEnabled", apiKeyProperties.isConfigured());
        status.put("time", OffsetDateTime.now().toString());
        return Results.success(status);
    }

    @GetMapping("/readiness")
    public ResponseEntity<Results<Map<String, Object>>> readiness() {
        CachedReadiness snapshot = cachedReadiness;
        if (isFresh(snapshot)) {
            return snapshot.response();
        }
        if (snapshot != null) {
            CompletableFuture<ResponseEntity<Results<Map<String, Object>>>> refresh =
                    refreshReadinessInBackground();
            if (isWithinMaxStale(snapshot)) {
                return snapshot.response();
            }
            try {
                return refresh.join();
            } catch (RuntimeException error) {
                return staleReadiness();
            }
        }
        synchronized (readinessLock) {
            snapshot = cachedReadiness;
            if (isFresh(snapshot)) {
                return snapshot.response();
            }
            ResponseEntity<Results<Map<String, Object>>> response = probeReadiness();
            cachedReadiness = new CachedReadiness(System.nanoTime(), response);
            return response;
        }
    }

    private CompletableFuture<ResponseEntity<Results<Map<String, Object>>>>
    refreshReadinessInBackground() {
        synchronized (readinessLock) {
            if (readinessRefresh != null && !readinessRefresh.isDone()) {
                return readinessRefresh;
            }
            CompletableFuture<ResponseEntity<Results<Map<String, Object>>>> refresh =
                    probeReadinessAsync();
            readinessRefresh = refresh;
            refresh.whenComplete((response, error) -> {
                synchronized (readinessLock) {
                    if (error == null && response != null) {
                        cachedReadiness = new CachedReadiness(System.nanoTime(), response);
                    }
                    if (readinessRefresh == refresh) {
                        readinessRefresh = null;
                    }
                }
            });
            return refresh;
        }
    }

    private ResponseEntity<Results<Map<String, Object>>> probeReadiness() {
        return probeReadinessAsync().join();
    }

    private CompletableFuture<ResponseEntity<Results<Map<String, Object>>>> probeReadinessAsync() {
        CompletableFuture<Boolean> mysqlProbe = probe(this::mysqlUp);
        CompletableFuture<Boolean> redisProbe = probe(this::redisUp);
        CompletableFuture<VectorStoreStatus> storeProbe = probe(this::safeVectorStatus);
        CompletableFuture<VectorRebuildReport> indexProbe = probe(this::safeIndexStatus);

        return CompletableFuture.allOf(mysqlProbe, redisProbe, storeProbe, indexProbe)
                .thenApply(ignored -> readinessResponse(
                        mysqlProbe.join(),
                        redisProbe.join(),
                        storeProbe.join(),
                        indexProbe.join()));
    }

    private ResponseEntity<Results<Map<String, Object>>> readinessResponse(
            boolean mysqlUp,
            boolean redisUp,
            VectorStoreStatus store,
            VectorRebuildReport index) {
        Map<String, Object> components = new LinkedHashMap<>();
        boolean vectorUp = store.available() && "READY".equals(index.status())
                && index.activeChunks() == index.indexedChunks();

        components.put("mysql", mysqlUp ? "UP" : "DOWN");
        components.put("redis", redisUp ? "UP" : "DOWN");
        components.put("model", ragProviderResolver.isMockMode() ? "MOCK" : "CONFIGURED");
        components.put("vector", Map.of(
                "status", vectorUp ? "UP" : "DOWN",
                "type", store.type(),
                "collection", store.collection(),
                "activeChunks", index.activeChunks(),
                "indexedChunks", index.indexedChunks()));

        boolean ready = mysqlUp && redisUp && vectorUp;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ready ? "UP" : "DOWN");
        body.put("components", components);
        body.put("time", OffsetDateTime.now().toString());
        Results<Map<String, Object>> result = ready
                ? Results.success(body)
                : Results.failed("503", "Required dependency is unavailable", body);
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(result);
    }

    private ResponseEntity<Results<Map<String, Object>>> staleReadiness() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "DOWN");
        body.put("reason", "READINESS_PROBE_STALE");
        body.put("time", OffsetDateTime.now().toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Results.failed("503", "Readiness probe result is stale", body));
    }

    private boolean isFresh(CachedReadiness snapshot) {
        if (snapshot == null || readinessCacheMillis <= 0) {
            return false;
        }
        return ageNanos(snapshot) < readinessCacheMillis * 1_000_000L;
    }

    private boolean isWithinMaxStale(CachedReadiness snapshot) {
        return readinessMaxStaleMillis > 0
                && ageNanos(snapshot) < readinessMaxStaleMillis * 1_000_000L;
    }

    private long ageNanos(CachedReadiness snapshot) {
        return Math.max(0, System.nanoTime() - snapshot.createdAtNanos());
    }

    private <T> CompletableFuture<T> probe(java.util.function.Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, healthProbeExecutor);
    }

    private boolean mysqlUp() {
        try {
            return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class));
        } catch (RuntimeException error) {
            return false;
        }
    }

    private boolean redisUp() {
        try (RedisConnection connection = redis.getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping());
        } catch (RuntimeException error) {
            return false;
        }
    }

    private VectorStoreStatus safeVectorStatus() {
        try {
            return vectorStore.status();
        } catch (RuntimeException error) {
            return new VectorStoreStatus(vectorStoreType, "unavailable", false,
                    error.getClass().getSimpleName());
        }
    }

    private VectorRebuildReport safeIndexStatus() {
        try {
            return vectorIndex.status();
        } catch (RuntimeException error) {
            return new VectorRebuildReport(vectorStoreType, "unavailable", "unavailable",
                    0, 0, 0, "FAILED");
        }
    }

    private record CachedReadiness(
            long createdAtNanos,
            ResponseEntity<Results<Map<String, Object>>> response) {
    }
}
