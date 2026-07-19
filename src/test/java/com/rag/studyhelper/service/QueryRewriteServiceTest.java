package com.rag.studyhelper.service;

import com.rag.studyhelper.model.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryRewriteServiceTest {

    @Mock
    private ChatLanguageModel chatModel;

    @InjectMocks
    private QueryRewriteService queryRewriteService;

    @Test
    void needsRewrite_shortQuestion() {
        assertTrue(QueryRewriteService.needsRewrite("它呢", 5));
    }

    @Test
    void needsRewrite_pronounInLongQuestion() {
        assertTrue(QueryRewriteService.needsRewrite("Spring Boot 它有什么好处", 5));
    }

    @Test
    void needsRewrite_selfContainedQuestion() {
        assertFalse(QueryRewriteService.needsRewrite("向量检索的基本原理是什么", 5));
    }

    @Test
    void rewrite_skipsWhenNoHistory() {
        String question = "RAG 是什么";
        assertEquals(question, queryRewriteService.rewrite(question, Collections.emptyList(), 5));
        verify(chatModel, never()).generate(anyList());
    }

    @Test
    void rewrite_callsModelWhenHistoryAndPronounPresent() {
        List<ChatMessage> history = Arrays.asList(
                new ChatMessage("user", "Java 要学哪些框架"),
                new ChatMessage("assistant", "Spring Boot")
        );
        when(chatModel.generate(anyList())).thenReturn(Response.from(AiMessage.from("Spring Boot 框架有哪些优势")));

        String rewritten = queryRewriteService.rewrite("它有什么好处", history, 5);

        assertEquals("Spring Boot 框架有哪些优势", rewritten);
        verify(chatModel).generate(anyList());
    }
}
