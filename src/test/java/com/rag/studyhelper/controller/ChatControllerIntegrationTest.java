package com.rag.studyhelper.controller;

import com.rag.studyhelper.model.ChatRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.*;

/**
 * ChatController 的集成测试。
 * 启动真实 HTTP 服务器（随机端口），调用真实 LLM，验证 SSE 响应。
 * 注意：需要配置有效的 API Key，会消耗 LLM 调用额度。
 * 该测试在 mvn package 时被排除，需手动运行：
 * mvn test -Dtest=ChatControllerIntegrationTest
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatControllerIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ChatControllerIntegrationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldReturnSseStreamFromRealLlm() {
        String url = "http://localhost:" + port + "/api/chat";
        log.info("POST {} (调用真实 LLM)", url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<ChatRequest> entity = new HttpEntity<>(new ChatRequest("s-test", "用一句话介绍 RAG"), headers);

        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, String.class);

        log.info("HTTP {}", response.getStatusCodeValue());

        String body = response.getBody();
        log.info("SSE 响应:\n{}", body);

        org.junit.jupiter.api.Assertions.assertTrue(body.startsWith("data:"), "SSE should start with data: prefix");
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("[DONE]"), "Should end with [DONE] SSE event");
        log.info("测试通过，LLM 返回正常");
    }
}
