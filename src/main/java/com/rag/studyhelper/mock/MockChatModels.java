package com.rag.studyhelper.mock;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock Chat / Streaming — 基于 prompt 中的参考文档片段生成演示回答。
 */
public final class MockChatModels {

    private static final Pattern QUESTION_BLOCK = Pattern.compile("## 问题\\s*\\n(.+?)\\s*(?:\\Z|$)", Pattern.DOTALL);

    private MockChatModels() {
    }

    public static ChatLanguageModel chatLanguageModel() {
        return messages -> Response.from(AiMessage.from(buildAnswer(extractText(messages))));
    }

    public static StreamingChatLanguageModel streamingChatLanguageModel() {
        return (messages, handler) -> {
            String answer = buildAnswer(extractText(messages));
            for (int i = 0; i < answer.length(); i += 1) {
                handler.onNext(String.valueOf(answer.charAt(i)));
            }
            handler.onComplete(Response.from(AiMessage.from(answer)));
        };
    }

    static String extractText(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        ChatMessage last = messages.get(messages.size() - 1);
        if (last instanceof UserMessage) {
            return ((UserMessage) last).singleText();
        }
        return last.text();
    }

    static String buildAnswer(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return "Mock 演示：空问题。";
        }
        String question = extractQuestion(prompt);
        if (prompt.contains("## 参考文档")) {
            int docStart = prompt.indexOf("## 参考文档");
            int constraintStart = prompt.indexOf("## 约束", docStart);
            String docs = constraintStart > docStart
                    ? prompt.substring(docStart + "## 参考文档".length(), constraintStart).trim()
                    : prompt.substring(docStart + "## 参考文档".length()).trim();
            String excerpt = docs.length() > 480 ? docs.substring(0, 480) + "…" : docs;
            return "根据 [来源:演示文档] 的记载：\n\n"
                    + excerpt
                    + "\n\n针对您的问题「" + question + "」，以上片段来自已入库知识库。（Mock 模式，无需 API Key）";
        }
        if (prompt.contains("查询改写助手")) {
            return simpleRewrite(prompt);
        }
        return "Mock 演示：知识库中未检索到足够相关的文档片段。您的问题是：「" + question + "」。"
                + "请先上传或扫描 `data/docs/` 下的演示文档后再提问。";
    }

    private static String extractQuestion(String prompt) {
        Matcher matcher = QUESTION_BLOCK.matcher(prompt);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return prompt.trim();
    }

    private static String simpleRewrite(String prompt) {
        int qIdx = prompt.lastIndexOf("## 最新问题");
        if (qIdx < 0) {
            return extractQuestion(prompt);
        }
        String tail = prompt.substring(qIdx);
        String question = tail.replace("## 最新问题", "").trim();
        if (prompt.contains("RAG") || prompt.contains("向量")) {
            return question.replace("它", "RAG").replace("他", "RAG").replace("这", "RAG");
        }
        return question;
    }
}
