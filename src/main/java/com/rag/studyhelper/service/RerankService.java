package com.rag.studyhelper.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private static final int TIMEOUT_MS = 60_000;

    @Value("${langchain4j.open-ai.rerank-model.api-key}")
    String apiKey;

    @Value("${langchain4j.open-ai.rerank-model.base-url}")
    String baseUrl;

    @Value("${langchain4j.open-ai.rerank-model.model-name}")
    String modelName;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public RerankService() {
        this.restTemplate = createRestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    // package-private for testing
    RerankService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    private static RestTemplate createRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MS);
        factory.setReadTimeout(TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    public List<TextSegment> rerank(String query, List<TextSegment> documents) {
        if (documents.isEmpty()) {
            return documents;
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelName);
        requestBody.put("query", query);
        requestBody.put("documents", documents.stream()
                .map(TextSegment::text)
                .collect(Collectors.toList()));
        int topN = Math.min(5, documents.size());
        requestBody.put("top_n", topN);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        try {
            String url = baseUrl + "/rerank";
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.info("Calling SiliconFlow Rerank: {} documents, query=\"{}\"", documents.size(), truncate(query, 50));
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            String body = response.getBody();
            if (body != null && !body.isEmpty()) {
                return parseAndReorder(body, documents);
            }
        } catch (Exception e) {
            log.warn("Rerank API call failed, falling back to original order: {}", e.getMessage());
        }

        return documents;
    }

    private List<TextSegment> parseAndReorder(String responseBody, List<TextSegment> original) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) {
                log.warn("Unexpected rerank response format: no 'results' array");
                return original;
            }

            List<TextSegment> reranked = new ArrayList<>();
            for (JsonNode result : results) {
                int index = result.get("index").asInt();
                if (index >= 0 && index < original.size()) {
                    reranked.add(original.get(index));
                }
            }

            log.info("Rerank returned {} results (from {} input)", reranked.size(), original.size());
            return reranked;
        } catch (Exception e) {
            log.warn("Failed to parse rerank response, falling back: {}", e.getMessage());
            return original;
        }
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
