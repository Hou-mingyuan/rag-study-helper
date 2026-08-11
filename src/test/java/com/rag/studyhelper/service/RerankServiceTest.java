package com.rag.studyhelper.service;

import com.rag.studyhelper.config.RagProviderResolver;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RerankServiceTest {

    @Mock
    private RagProviderResolver ragProviderResolver;

    private RerankService rerankService;

    @BeforeEach
    void setUp() {
        rerankService = new RerankService();
        ReflectionTestUtils.setField(rerankService, "ragProviderResolver", ragProviderResolver);
    }

    @Test
    void mockRerank_prefersTokenOverlap() {
        when(ragProviderResolver.isMockMode()).thenReturn(true);
        List<TextSegment> docs = Arrays.asList(
                TextSegment.from("红烧肉的做法与调料"),
                TextSegment.from("Spring Boot 微服务部署与监控"),
                TextSegment.from("向量检索与 embedding 基础")
        );

        List<TextSegment> ranked = rerankService.rerank("Spring Boot 部署", docs, 2);

        assertEquals(2, ranked.size());
        assertTrue(ranked.get(0).text().contains("Spring Boot"));
    }

    @Test
    void rerank_emptyDocuments_returnsEmpty() {
        assertTrue(rerankService.rerank("query", Arrays.asList(), 3).isEmpty());
    }
}
