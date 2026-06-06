package com.rag.studyhelper.service;

import com.rag.studyhelper.model.ChatMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    @Autowired
    private OpenAiChatModel chatModel;

    public String rewrite(String originalQuestion, List<ChatMessage> history) {
        // Only rewrite when there is history and the question likely needs context
        if (history.isEmpty() || !needsRewrite(originalQuestion)) {
            return originalQuestion;
        }

        log.info("Rewriting question with {} rounds of history", history.size() / 2);

        String prompt = buildRewritePrompt(originalQuestion, history);
        String rewritten = chatModel.generate(prompt).trim();

        log.info("Rewritten: \"{}\" → \"{}\"", originalQuestion, rewritten);
        return rewritten;
    }

    static boolean needsRewrite(String question) {
        if (question.length() < 5) {
            return true;
        }
        String s = question;
        return s.contains("这") || s.contains("那")
                || s.contains("它") || s.contains("他") || s.contains("她")
                || s.contains("它们") || s.contains("他们") || s.contains("她们")
                || s.contains("该") || s.contains("此")
                || s.contains("上面") || s.contains("上边") || s.contains("上述")
                || s.contains("刚才") || s.contains("之前");
    }

    private String buildRewritePrompt(String question, List<ChatMessage> history) {
        String historyText = history.stream()
                .map(m -> (m.getRole().equals("user") ? "用户" : "助手") + "：" + m.getContent())
                .collect(Collectors.joining("\n"));

        return "你是一个查询改写助手。根据对话历史，将用户最新问题改写为独立、完整、精确的搜索查询。\n\n"
                + "## 要求\n"
                + "- 只输出改写后的查询，不要任何解释\n"
                + "- 保留原问题的所有关键信息\n"
                + "- 补全代词/指代，消除歧义\n"
                + "- 如果不需要改写，输出原始问题\n\n"
                + "## 对话历史\n" + historyText + "\n\n"
                + "## 最新问题\n" + question + "\n\n"
                + "## 改写后的查询";
    }
}
