package com.rag.studyhelper.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.studyhelper.config.RagProviderResolver;
import com.rag.studyhelper.mock.MockEmbeddingModel;
import com.rag.studyhelper.model.RankedCandidate;
import com.rag.studyhelper.model.RerankCandidate;
import com.rag.studyhelper.model.RerankOutcome;
import dev.langchain4j.data.segment.TextSegment;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @Value("${langchain4j.open-ai.rerank-model.api-key:}")
    String apiKey;

    @Value("${langchain4j.open-ai.rerank-model.base-url:}")
    String baseUrl;

    @Value("${langchain4j.open-ai.rerank-model.model-name:}")
    String modelName;

    @Value("${app.rag.rerank-max-retries:2}")
    int maxRetries;

    @Value("${app.rag.rerank-backoff-millis:100}")
    long retryBackoffMillis;

    @Autowired
    private RagProviderResolver ragProviderResolver;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public RerankService() {
        this(new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build(), new ObjectMapper());
    }

    RerankService(OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public RerankOutcome rerankCandidates(String query, List<RerankCandidate> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return new RerankOutcome(List.of(), "none", false, null);
        }
        int limit = Math.min(Math.max(topN, 1), candidates.size());
        if (ragProviderResolver.isMockMode()) {
            List<String> queryTokens = MockEmbeddingModel.tokenize(query);
            List<RankedCandidate> ranked = candidates.stream()
                    .map(candidate -> new RankedCandidate(candidate.id(), candidate.text(),
                            candidate.retrievalScore(), mockScore(queryTokens, candidate.text())))
                    .sorted(Comparator
                            .comparingDouble((RankedCandidate candidate) -> candidate.rerankScore())
                            .reversed()
                            .thenComparing(Comparator
                                    .comparingDouble(RankedCandidate::retrievalScore).reversed()))
                    .limit(limit)
                    .toList();
            return new RerankOutcome(ranked, "mock", false, null);
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("model", modelName);
            payload.put("query", query);
            payload.put("documents", candidates.stream().map(RerankCandidate::text).toList());
            payload.put("top_n", limit);
            Request request = new Request.Builder()
                    .url(stripTrailingSlash(baseUrl) + "/rerank")
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(JSON, objectMapper.writeValueAsBytes(payload)))
                    .build();
            int attempts = Math.max(0, maxRetries) + 1;
            for (int attempt = 0; attempt < attempts; attempt++) {
                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        RerankOutcome parsed = parse(response.body().bytes(), candidates, limit);
                        if (parsed != null) {
                            return parsed;
                        }
                        return fallback(candidates, limit, "invalid response");
                    }
                    if (isRetryable(response.code()) && attempt + 1 < attempts) {
                        waitBeforeRetry(response.header("Retry-After"), attempt);
                        continue;
                    }
                    return fallback(candidates, limit, "HTTP " + response.code());
                } catch (java.io.IOException error) {
                    if (attempt + 1 >= attempts) {
                        throw error;
                    }
                    waitBeforeRetry(null, attempt);
                }
            }
            return fallback(candidates, limit, "retry exhausted");
        } catch (Exception error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Rerank unavailable; retrieval order retained: {}",
                    error.getClass().getSimpleName());
            return fallback(candidates, limit, error.getClass().getSimpleName());
        }
    }

    public List<TextSegment> rerank(String query, List<TextSegment> documents, int topN) {
        List<RerankCandidate> candidates = new ArrayList<>(documents.size());
        Map<String, TextSegment> byId = new HashMap<>();
        for (int index = 0; index < documents.size(); index++) {
            String id = String.valueOf(index);
            candidates.add(new RerankCandidate(id, documents.get(index).text(), 0d));
            byId.put(id, documents.get(index));
        }
        return rerankCandidates(query, candidates, topN).candidates().stream()
                .map(candidate -> byId.get(candidate.id()))
                .toList();
    }

    private RerankOutcome parse(byte[] responseBody, List<RerankCandidate> original, int limit)
            throws java.io.IOException {
        JsonNode results = objectMapper.readTree(responseBody).path("results");
        if (!results.isArray()) {
            return null;
        }
        List<RankedCandidate> ranked = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= original.size() || !seen.add(index)) {
                continue;
            }
            RerankCandidate source = original.get(index);
            ranked.add(new RankedCandidate(source.id(), source.text(), source.retrievalScore(),
                    item.has("relevance_score") ? item.path("relevance_score").asDouble() : null));
            if (ranked.size() == limit) {
                break;
            }
        }
        return ranked.isEmpty() ? null : new RerankOutcome(List.copyOf(ranked), "external", false, null);
    }

    private RerankOutcome fallback(List<RerankCandidate> candidates, int limit, String detail) {
        List<RankedCandidate> ranked = candidates.stream().limit(limit)
                .map(candidate -> new RankedCandidate(candidate.id(), candidate.text(),
                        candidate.retrievalScore(), null))
                .toList();
        return new RerankOutcome(ranked, "fallback", true, detail);
    }

    private boolean isRetryable(int status) {
        return status == 429 || status >= 500;
    }

    private void waitBeforeRetry(String retryAfter, int attempt) throws InterruptedException {
        long delay = retryDelayMillis(retryAfter, attempt);
        if (delay > 0) {
            Thread.sleep(delay);
        }
    }

    private long retryDelayMillis(String retryAfter, int attempt) {
        if (retryAfter != null) {
            try {
                return Math.min(Long.parseLong(retryAfter.trim()) * 1000L, 5_000L);
            } catch (NumberFormatException ignored) {
                // Fall through to the bounded exponential delay.
            }
        }
        long base = Math.max(0L, retryBackoffMillis);
        return Math.min(base * (1L << Math.min(attempt, 5)), 5_000L);
    }

    private double mockScore(List<String> queryTokens, String document) {
        if (queryTokens.isEmpty()) {
            return 0d;
        }
        List<String> documentTokens = MockEmbeddingModel.tokenize(document);
        long overlap = queryTokens.stream().filter(documentTokens::contains).distinct().count();
        return (double) overlap / queryTokens.stream().distinct().count();
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Rerank base URL is not configured");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
