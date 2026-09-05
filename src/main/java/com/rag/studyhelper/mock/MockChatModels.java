package com.rag.studyhelper.mock;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock Chat / Streaming — 基于 prompt 中的参考文档片段生成演示回答。
 */
public final class MockChatModels {

    private static final Pattern QUESTION_BLOCK = Pattern.compile("## 问题\\s*\\n(.+?)\\s*(?:\\Z|$)", Pattern.DOTALL);
    private static final Pattern GROUNDED_QUESTION = Pattern.compile("(?:^|\\n)QUESTION\\s*\\n(.+?)\\s*$", Pattern.DOTALL);
    private static final Pattern REWRITE_QUESTION = Pattern.compile(
            "<question>\\s*(.*?)\\s*</question>", Pattern.DOTALL);
    private static final Pattern GROUNDED_DOCUMENT = Pattern.compile(
            "\\[DOCUMENT\\s+documentId=(\\d+)\\s+chunkId=(\\d+)[^]]*]\\s*(.*?)\\s*\\[/DOCUMENT]",
            Pattern.DOTALL);
    private static final String NO_ANSWER =
            "根据当前知识空间的资料，没有找到可支持该问题的信息。";

    private MockChatModels() {
    }

    public static ChatModel chatLanguageModel() {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return response(buildAnswer(extractText(request.messages())));
            }
        };
    }

    public static StreamingChatModel streamingChatLanguageModel() {
        return new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                String answer = buildAnswer(extractText(request.messages()));
                for (int i = 0; i < answer.length(); i += 12) {
                    handler.onPartialResponse(answer.substring(i, Math.min(i + 12, answer.length())));
                }
                handler.onCompleteResponse(response(answer));
            }
        };
    }

    private static ChatResponse response(String answer) {
        return ChatResponse.builder().aiMessage(AiMessage.from(answer)).build();
    }

    static String extractText(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        ChatMessage last = messages.get(messages.size() - 1);
        if (last instanceof UserMessage) {
            return ((UserMessage) last).singleText();
        }
        if (last instanceof AiMessage) {
            return ((AiMessage) last).text();
        }
        return last.toString();
    }

    static String buildAnswer(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return "Mock 演示：空问题。";
        }
        String question = extractQuestion(prompt);
        if (prompt.contains("EXCERPTS")) {
            Matcher document = GROUNDED_DOCUMENT.matcher(prompt);
            List<DocumentExcerpt> candidates = new ArrayList<>();
            while (document.find()) {
                ScoredExcerpt excerpt = relevantExcerpt(document.group(3), question);
                if (!excerpt.text().isBlank()) {
                    candidates.add(new DocumentExcerpt(document.group(1), document.group(2),
                            excerpt.text(), excerpt.score()));
                }
            }
            if (candidates.isEmpty()) {
                return NO_ANSWER;
            }
            int bestScore = candidates.stream().mapToInt(DocumentExcerpt::score).max().orElse(0);
            int minimumScore = Math.min(bestScore,
                    Math.max(12, (int) Math.ceil(bestScore * 0.45d)));
            StringBuilder answer = new StringBuilder("根据资料：\n\n");
            int matched = 0;
            for (DocumentExcerpt candidate : candidates) {
                if (candidate.score() < minimumScore || matched >= 3 || answer.length() >= 1400) {
                    continue;
                }
                if (matched > 0) {
                    answer.append("\n\n");
                }
                answer.append(candidate.text()).append(" [").append(candidate.documentId())
                        .append(":").append(candidate.chunkId()).append("]");
                matched++;
            }
            if (matched == 0) {
                return NO_ANSWER;
            }
            return answer + "\n\n（Mock 模式，本回答仅用于本地验收。）";
        }
        if (prompt.contains("查询改写助手")) {
            return simpleRewrite(prompt);
        }
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
        return NO_ANSWER;
    }

    private static String extractQuestion(String prompt) {
        Matcher grounded = GROUNDED_QUESTION.matcher(prompt);
        if (grounded.find()) {
            return grounded.group(1).trim();
        }
        Matcher matcher = QUESTION_BLOCK.matcher(prompt);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return prompt.trim();
    }

    private static ScoredExcerpt relevantExcerpt(String content, String question) {
        String normalized = content == null ? "" : content.replace('\r', '\n').trim();
        if (normalized.isBlank()) {
            return new ScoredExcerpt("", Integer.MIN_VALUE);
        }
        String[] paragraphs = normalized.split("\\n\\s*\\n");
        int bestIndex = 0;
        int bestScore = Integer.MIN_VALUE;
        String compactQuestion = compact(question);
        Set<String> queryTerms = queryTerms(question);
        for (int index = 0; index < paragraphs.length; index++) {
            String paragraph = paragraphs[index].trim();
            String compactParagraph = compact(paragraph);
            int score = 0;
            if (paragraph.contains("问：") || paragraph.contains("→")
                    || paragraph.contains("演示问答示例")) {
                score -= 1000;
            }
            if (paragraph.startsWith("#")) {
                String heading = compact(paragraph.replaceFirst("^#+\\s*", ""));
                if (heading.length() >= 2 && compactQuestion.contains(heading)) {
                    score += 500;
                }
            }
            for (String term : queryTerms) {
                if (compactParagraph.contains(term)) {
                    score += term.length() * term.length();
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            }
        }
        if (bestScore < 8) {
            return new ScoredExcerpt("", bestScore);
        }
        int startIndex = bestIndex;
        if (!paragraphs[startIndex].trim().startsWith("#")
                && startIndex > 0 && paragraphs[startIndex - 1].trim().startsWith("#")) {
            startIndex--;
        }
        StringBuilder excerpt = new StringBuilder();
        for (int index = startIndex; index < paragraphs.length && excerpt.length() < 520; index++) {
            String paragraph = paragraphs[index].trim();
            if (index > startIndex && paragraph.startsWith("#")) {
                break;
            }
            if (paragraph.contains("演示问答示例")
                    || paragraph.contains("问：") || paragraph.contains("→")) {
                break;
            }
            if (excerpt.length() > 0) {
                excerpt.append("\n\n");
            }
            excerpt.append(paragraph);
        }
        if (excerpt.length() > 520) {
            return new ScoredExcerpt(excerpt.substring(0, 520).trim() + "…", bestScore);
        }
        return new ScoredExcerpt(excerpt.toString().trim(), bestScore);
    }

    private static Set<String> queryTerms(String question) {
        Set<String> terms = new LinkedHashSet<>();
        String source = question == null ? "" : question.toLowerCase(Locale.ROOT);
        Matcher ascii = Pattern.compile("[a-z0-9][a-z0-9_+.-]{1,}").matcher(source);
        while (ascii.find()) {
            terms.add(ascii.group());
        }
        Matcher han = Pattern.compile("[\\p{IsHan}]+").matcher(source);
        while (han.find()) {
            String run = han.group();
            for (int size = 2; size <= Math.min(4, run.length()); size++) {
                for (int offset = 0; offset + size <= run.length(); offset++) {
                    String term = run.substring(offset, offset + size);
                    if (!isQuestionStopTerm(term)) {
                        terms.add(term);
                    }
                }
            }
        }
        return terms;
    }

    private static boolean isQuestionStopTerm(String term) {
        return "什么,的是,请问,如何,怎么,哪些,有哪,是否,可以,能否,为何,为什么"
                .contains(term);
    }

    private static String compact(String value) {
        return value == null ? "" : value
                .replaceAll("[\\p{P}\\p{Z}\\s]", "")
                .toLowerCase(Locale.ROOT);
    }

    private record ScoredExcerpt(String text, int score) {
    }

    private record DocumentExcerpt(String documentId, String chunkId, String text, int score) {
    }

    private static String simpleRewrite(String prompt) {
        Matcher markedQuestion = REWRITE_QUESTION.matcher(prompt);
        if (markedQuestion.find()) {
            String question = markedQuestion.group(1).trim();
            if (prompt.contains("RAG") || prompt.contains("向量")) {
                return question.replace("它", "RAG").replace("他", "RAG").replace("这", "RAG");
            }
            return question;
        }
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
