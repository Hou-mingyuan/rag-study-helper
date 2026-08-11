package com.rag.studyhelper.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.studyhelper.model.RetrievalSnippet;
import com.rag.studyhelper.service.ChatRequestRegistry;
import com.rag.studyhelper.service.RagQueryService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private RagQueryService rag;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ChatController controller = new ChatController(rag, new ChatRequestRegistry(),
                new ObjectMapper(), new SyncTaskExecutor(), 30_000);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void successStreamHasRetrievalTokensAndExactlyOneDoneTerminal() throws Exception {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<List<RetrievalSnippet>> retrieval = invocation.getArgument(3);
            dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler =
                    invocation.getArgument(5);
            retrieval.accept(List.of(new RetrievalSnippet("guide.md", "RAG", 7L, 21L,
                    0, 4, "核心概念", 0.9, 0.98, "mock", "vector-1")));
            handler.onPartialResponse("RAG 是检索增强生成。[7:21]");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("RAG 是检索增强生成。[7:21]"))
                    .build());
            return null;
        }).when(rag).streamAnswer(anyLong(), anyString(), anyString(), any(), any(), any());

        MvcResult result = mvc.perform(post("/api/spaces/3/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"session-1\",\"question\":\"RAG 是什么？\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString();
        assertEquals(1, occurrences(body, "event:done"));
        assertEquals(0, occurrences(body, "event:error"));
        assertEquals(1, occurrences(body, "event:retrieval"));
        assertEquals(1, occurrences(body, "event:token"));
    }

    @Test
    void downstreamErrorHasErrorTerminalAndNeverDone() throws Exception {
        doAnswer(invocation -> {
            dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler =
                    invocation.getArgument(5);
            handler.onError(new IllegalStateException("provider failed"));
            return null;
        }).when(rag).streamAnswer(anyLong(), anyString(), anyString(), any(), any(), any());

        MvcResult result = mvc.perform(post("/api/spaces/3/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"session-2\",\"question\":\"RAG 是什么？\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mvc.perform(asyncDispatch(result)).andReturn()
                .getResponse().getContentAsString();
        assertEquals(1, occurrences(body, "event:error"));
        assertEquals(0, occurrences(body, "event:done"));
        assertEquals(0, occurrences(body, "provider failed"));
    }

    private int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
