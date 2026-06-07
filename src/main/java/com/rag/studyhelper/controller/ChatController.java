package com.rag.studyhelper.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rag.studyhelper.config.IpRateLimit;
import com.rag.studyhelper.model.ChatRequest;
import com.rag.studyhelper.service.RagQueryService;
import com.rag.studyhelper.utils.Results;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
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

/**
 * 聊天接口
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private RagQueryService ragQueryService;

    /**
     * 整条链路逻辑
     * 前端浏览器                    Java 后端                    LangChain OpenAI  API
     * │                           │                            │
     * │── POST /api/chat ────────→│                            │
     * │                           │── HTTP streaming request ──→│
     * │                           │                            │
     * │   ← SSE: {"token":"你"}   │◄── "你" (onNext) ────────── │
     * │   ← SSE: {"token":"好"}   │◄── "好" (onNext) ────────── │
     * │   ← SSE: [DONE]           │◄── complete ─────────────── │
     * <br/>
     * 关于请求中断：LangChain4j 0.35.0 未暴露 HTTP 连接句柄，无法主动取消 LLM 调用
     * 升级到 LangChain4j 1.0+（需 JDK 17）后可做到真正的请求级取消
     * 为了方便后续升级，我没有做手动的 Java 后端与 LLM 的请求中断交互
     * 只是让前端和java后端SSE断开连接
     */
    @IpRateLimit("chat")
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        // 与前端交互的 SSE 响应
        SseEmitter emitter = new SseEmitter(60_000L);

        try {
            ragQueryService.streamAnswer(request.getSessionId(), request.getQuestion(), new StreamingResponseHandler<AiMessage>() {
                // 处理 LLM 返回的分词结果
                @Override
                public void onNext(String token) {
                    try {
                        Map<String, String> data = new HashMap<>();
                        data.put("token", token);
                        // 发送 json 格式给前端
                        emitter.send(SseEmitter.event()
                                .data(OBJECT_MAPPER.writeValueAsString(data)));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                }

                // 处理 SSE 结束信息
                @Override
                public void onComplete(Response<AiMessage> response) {
                    try {
                        emitter.send(SseEmitter.event().data("[DONE]"));
                        // 关闭 SSE 连接
                        emitter.complete();
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                }

                // 处理失败信息
                @Override
                public void onError(Throwable error) {
                    log.error("LLM 流式处理失败", error);
                    try {
                        // 错误信息通过 SSE 响应返回给前端
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
            log.error("chat 流式处理失败", e);
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
