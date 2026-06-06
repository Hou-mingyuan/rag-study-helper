package com.rag.demo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.demo.config.RateLimit;
import com.rag.demo.model.ChatRequest;
import com.rag.demo.service.RagQueryService;
import com.rag.demo.utils.Results;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private RagQueryService ragQueryService;

    /**
     * 关于请求中断：LangChain4j 0.35.0 未暴露 HTTP 连接句柄，无法主动取消 LLM 调用
     *            升级到 LangChain4j 1.0+（需 JDK 17）后可做到真正的请求级取消
     *            所以我只是前端加了假的终止链接，后续可无痛升级
     */
    @RateLimit
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L);

        try {
            ragQueryService.streamAnswer(request.getSessionId(), request.getQuestion(), new RagQueryService.StreamingCallback() {
                @Override
                public void onToken(String token) {
                    try {
                        Map<String, String> data = new HashMap<>();
                        data.put("token", token);
                        emitter.send(SseEmitter.event()
                                .data(OBJECT_MAPPER.writeValueAsString(data)));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                }

                @Override
                public void onComplete() {
                    try {
                        emitter.send(SseEmitter.event().data("[DONE]"));
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                }

                @Override
                public void onError(Throwable error) {
                    log.error("流式处理失败", error);
                    try {
                        Results<Void> err = Results.failed("500", "流式处理失败: " + error.getMessage());
                        emitter.send(SseEmitter.event()
                                .name("error")
                                .data(OBJECT_MAPPER.writeValueAsString(err)));
                        emitter.send(SseEmitter.event().data("[DONE]"));
                    } catch (IOException e2) {
                        emitter.completeWithError(e2);
                    }
                    emitter.complete();
                }
            });
        } catch (Exception e) {
            log.error("流式处理失败", e);
            try {
                Results<Void> err = Results.failed("500", "流式处理失败: " + e.getMessage());
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(OBJECT_MAPPER.writeValueAsString(err)));
                emitter.send(SseEmitter.event().data("[DONE]"));
            } catch (IOException ignored) {
            }
            emitter.complete();
        }

        return emitter;
    }
}
