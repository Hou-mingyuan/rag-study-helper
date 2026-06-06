package com.rag.demo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RerankServiceTest {

    private RestTemplate restTemplate;
    private RerankService rerankService;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        rerankService = new RerankService(restTemplate);
        rerankService.apiKey = "test-api-key";
        rerankService.baseUrl = "https://api.siliconflow.cn/v1";
        rerankService.modelName = "BAAI/bge-reranker-v2-m3";
    }

    @Test
    void shouldReorderDocumentsByScore() {
        List<TextSegment> input = Arrays.asList(
                TextSegment.from("文档A内容"),
                TextSegment.from("文档B内容"),
                TextSegment.from("文档C内容")
        );

        // Mock SiliconFlow rerank API response
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode responseJson = mapper.createObjectNode();
        ArrayNode results = responseJson.putArray("results");
        ObjectNode r1 = results.addObject(); r1.put("index", 0); r1.put("relevance_score", 0.95);
        ObjectNode r2 = results.addObject(); r2.put("index", 2); r2.put("relevance_score", 0.82);
        ObjectNode r3 = results.addObject(); r3.put("index", 1); r3.put("relevance_score", 0.74);

        ResponseEntity<String> mockResponse = new ResponseEntity<>(
                responseJson.toString(), HttpStatus.OK);

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(mockResponse);

        List<TextSegment> result = rerankService.rerank("测试查询", input);

        assertNotNull(result);
        assertFalse(result.isEmpty());
        // Mock returns indices [0, 2, 1] — expected order: A, C, B
        assertEquals(3, result.size());
        assertEquals("文档A内容", result.get(0).text());
        assertEquals("文档C内容", result.get(1).text());
        assertEquals("文档B内容", result.get(2).text());
    }

    @Test
    void shouldFallbackToOriginalOrderWhenApiFails() {
        List<TextSegment> input = Arrays.asList(
                TextSegment.from("文档A"),
                TextSegment.from("文档B")
        );

        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new RuntimeException("API unavailable"));

        List<TextSegment> result = rerankService.rerank("测试查询", input);

        // Fallback preserves original order
        assertEquals(2, result.size());
        assertEquals("文档A", result.get(0).text());
        assertEquals("文档B", result.get(1).text());
    }

    @Test
    void shouldReturnEmptyWhenInputEmpty() {
        List<TextSegment> result = rerankService.rerank("测试查询", Arrays.asList());
        assertTrue(result.isEmpty());
    }
}
