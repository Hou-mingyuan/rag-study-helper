package com.rag.studyhelper.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.studyhelper.config.IpRateLimit;
import com.rag.studyhelper.model.ChatRequest;
import com.rag.studyhelper.model.RetrievalSnippet;
import com.rag.studyhelper.service.ChatRequestRegistry;
import com.rag.studyhelper.service.KnowledgeSpaceService;
import com.rag.studyhelper.service.RagQueryService;
import com.rag.studyhelper.utils.Results;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final RagQueryService ragQueryService;
    private final ChatRequestRegistry requests;
    private final ObjectMapper objectMapper;
    private final TaskExecutor chatExecutor;
    private final long streamTimeoutMillis;

    public ChatController(RagQueryService ragQueryService,
                          ChatRequestRegistry requests,
                          ObjectMapper objectMapper,
                          @Qualifier("chatTaskExecutor") TaskExecutor chatExecutor,
                          @Value("${app.rag.stream-timeout-millis:90000}") long streamTimeoutMillis) {
        this.ragQueryService = ragQueryService;
        this.requests = requests;
        this.objectMapper = objectMapper;
        this.chatExecutor = chatExecutor;
        this.streamTimeoutMillis = Math.max(10_000L, streamTimeoutMillis);
    }

    @IpRateLimit("chat")
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter legacyChat(@Valid @RequestBody ChatRequest request) {
        return stream(KnowledgeSpaceService.DEFAULT_SPACE_ID, request);
    }

    @IpRateLimit("chat")
    @PostMapping(value = "/spaces/{spaceId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable long spaceId, @Valid @RequestBody ChatRequest request) {
        return stream(spaceId, request);
    }

    @PostMapping("/spaces/{spaceId}/chat/requests/{requestId}/cancel")
    public Results<Boolean> cancel(@PathVariable long spaceId, @PathVariable String requestId) {
        return Results.success(requests.cancel(spaceId, requestId));
    }

    private SseEmitter stream(long spaceId, ChatRequest request) {
        SseEmitter emitter = new SseEmitter(streamTimeoutMillis);
        String requestId = requests.begin(spaceId);
        AtomicBoolean terminal = new AtomicBoolean();

        emitter.onTimeout(() -> finishCancelled(emitter, spaceId, requestId, terminal));
        emitter.onCompletion(() -> requests.complete(requestId));
        emitter.onError(error -> {
            requests.cancel(spaceId, requestId);
            requests.complete(requestId);
        });

        if (!send(emitter, "status", Map.of(
                "requestId", requestId,
                "state", "retrieving"))) {
            finishCancelled(emitter, spaceId, requestId, terminal);
            return emitter;
        }

        try {
            chatExecutor.execute(() -> executeStream(
                    emitter, spaceId, request, requestId, terminal));
        } catch (RuntimeException rejected) {
            log.error("RAG stream executor rejected request: requestId={}, errorType={}",
                    requestId, rejected.getClass().getSimpleName());
            if (terminal.compareAndSet(false, true)) {
                finish(emitter, requestId, "error", Map.of(
                        "error", Results.failed("503", "Chat service is busy")));
            }
        }
        return emitter;
    }

    private void executeStream(SseEmitter emitter, long spaceId, ChatRequest request,
                               String requestId, AtomicBoolean terminal) {
        try {
            ragQueryService.streamAnswer(spaceId, request.getSessionId(), request.getQuestion(),
                    snippets -> sendRetrieval(emitter, spaceId, requestId, terminal, snippets),
                    () -> requests.isCancelled(requestId),
                    new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String token) {
                            if (terminal.get() || requests.isCancelled(requestId)) {
                                return;
                            }
                            if (!send(emitter, "token", Map.of(
                                    "requestId", requestId,
                                    "token", token))) {
                                requests.cancel(spaceId, requestId);
                            }
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse response) {
                            if (!terminal.compareAndSet(false, true)) {
                                return;
                            }
                            if (requests.isCancelled(requestId)) {
                                finish(emitter, requestId, "cancelled", Map.of());
                                return;
                            }
                            finish(emitter, requestId, "done", Map.of("state", "completed"));
                        }

                        @Override
                        public void onError(Throwable error) {
                            if (!terminal.compareAndSet(false, true)) {
                                return;
                            }
                            if (error instanceof CancellationException
                                    || requests.isCancelled(requestId)) {
                                finish(emitter, requestId, "cancelled", Map.of());
                                return;
                            }
                            log.error("RAG stream failed: requestId={}, errorType={}",
                                    requestId, error.getClass().getSimpleName());
                            finish(emitter, requestId, "error", Map.of(
                                    "error", Results.failed("500", "Stream processing failed")));
                        }
                    });
        } catch (CancellationException cancelled) {
            if (terminal.compareAndSet(false, true)) {
                finish(emitter, requestId, "cancelled", Map.of());
            }
        } catch (Exception error) {
            log.error("RAG stream setup failed: requestId={}, errorType={}",
                    requestId, error.getClass().getSimpleName());
            if (terminal.compareAndSet(false, true)) {
                finish(emitter, requestId, "error", Map.of(
                        "error", Results.failed("500", "Stream setup failed")));
            }
        }
    }

    private void sendRetrieval(SseEmitter emitter, long spaceId, String requestId,
                               AtomicBoolean terminal, List<RetrievalSnippet> snippets) {
        if (terminal.get() || requests.isCancelled(requestId)) {
            return;
        }
        if (!send(emitter, "retrieval", Map.of(
                "requestId", requestId,
                "snippets", snippets))) {
            requests.cancel(spaceId, requestId);
        }
    }

    private void finishCancelled(SseEmitter emitter, long spaceId, String requestId,
                                 AtomicBoolean terminal) {
        requests.cancel(spaceId, requestId);
        if (terminal.compareAndSet(false, true)) {
            finish(emitter, requestId, "cancelled", Map.of());
        }
    }

    private void finish(SseEmitter emitter, String requestId, String event,
                        Map<String, Object> additional) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", requestId);
        payload.putAll(additional);
        send(emitter, event, payload);
        requests.complete(requestId);
        emitter.complete();
    }

    private boolean send(SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(objectMapper.writeValueAsString(payload), MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException error) {
            return false;
        }
    }
}
