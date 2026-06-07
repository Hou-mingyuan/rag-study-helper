package com.rag.studyhelper.service;

import com.rag.studyhelper.model.ChatMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 查询改写服务
 */
@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    @Autowired
    private OpenAiChatModel chatModel;

    /**
     * 改写逻辑
     */
    public String rewrite(String originalQuestion, List<ChatMessage> history, int rewriteWordCount) {
        // 如果没有上下文或者没有超过 rewriteWordCount 的字数，就没有改写的必要了
        if (history.isEmpty() || !needsRewrite(originalQuestion,rewriteWordCount)) {
            return originalQuestion;
        }

        log.info("rewrite question with {} rounds of history", history.size() / 2);

        String prompt = buildRewritePrompt(originalQuestion, history);
        String rewritten = chatModel.generate(prompt).trim();

        log.info("rewrite question : \"{}\" → \"{}\"", originalQuestion, rewritten);
        return rewritten;
    }

    /**
     * 是否需要改写
     */
    static boolean needsRewrite(String question, int rewriteWordCount) {
        if (question.length() < rewriteWordCount) {
            return true;
        }
        return question.contains("这") || question.contains("那")
                || question.contains("它") || question.contains("他") || question.contains("她")
                || question.contains("它们") || question.contains("他们") || question.contains("她们")
                || question.contains("该") || question.contains("此")
                || question.contains("上面") || question.contains("上边") || question.contains("上述")
                || question.contains("刚才") || question.contains("之前");
    }

    /**
     * 构建查询改写提示
     */
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
