package com.rag.demo.service;

import com.rag.demo.model.ChatMessage;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagQueryService {

    private static final Logger log = LoggerFactory.getLogger(RagQueryService.class);

    @Autowired
    private ConversationStore conversationStore;

    @Autowired
    private QueryRewriteService queryRewriteService;

    @Autowired
    private OpenAiStreamingChatModel streamingChatModel;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    @Value("${app.rag.score-threshold:0.80}")
    private double scoreThreshold;

    @Autowired
    private RerankService rerankService;

    public void streamAnswer(String sessionId, String question, StreamingCallback callback) {
        // 0. Load conversation history and rewrite query for retrieval
        List<ChatMessage> history = conversationStore.getHistory(sessionId);
        String searchQuery = queryRewriteService.rewrite(question, history);
        if (!searchQuery.equals(question)) {
            log.info("Search query rewritten: \"{}\" → \"{}\"", question, searchQuery);
        }

        // 1. Embed the rewritten query (for retrieval only)
        Embedding questionEmbedding = embeddingModel.embed(searchQuery).content();

//        todo 查询 rag 过滤 仅 chroma 和 milvus 使用
//        List<Long> accessibleDocIds = documentAccessService.getAccessibleDocIds(currentUserId);
//
//        // 构建过滤条件：公开的 OR 在我能看的私有文档列表里
//        Filter filter = MetadataFilterBuilder.metadataKey("visibility").isEqualTo("public");
//        if (!accessibleDocIds.isEmpty()) {
//            filter = filter.or(
//                    MetadataFilterBuilder.metadataKey("document_id").isIn(accessibleDocIds)
//            );
//        }

        // 2. Search top 20, then filter by score threshold
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(20)
//                todo 查询 rag 过滤 仅 chroma 和 milvus 使用
//                .filter(filter)
                .build();
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();
        log.info("Found {} matches total", matches.size());

        for (EmbeddingMatch<TextSegment> m : matches) {
            log.info("  score={} content={}", m.score(),
                    m.embedded().text().substring(0, Math.min(80, m.embedded().text().length())));
        }

        if (!matches.isEmpty()) {
            DoubleSummaryStatistics stats = matches.stream()
                    .mapToDouble(EmbeddingMatch::score).summaryStatistics();
            long above90 = matches.stream().filter(m -> m.score() >= 0.90).count();
            long above80 = matches.stream().filter(m -> m.score() >= 0.80 && m.score() < 0.90).count();
            long above70 = matches.stream().filter(m -> m.score() >= 0.70 && m.score() < 0.80).count();
            long below70 = matches.stream().filter(m -> m.score() < 0.70).count();
            log.info("Score distribution: max={} min={} avg={} | ≥0.90={} 0.80-0.89={} 0.70-0.79={} <0.70={}",
                    stats.getMax(), stats.getMin(), stats.getAverage(),
                    above90, above80, above70, below70);
        }

        // 3. Filter by relevance score
        double threshold = scoreThreshold;
        List<TextSegment> relevant = matches.stream()
                .filter(m -> m.score() >= threshold)
                .map(EmbeddingMatch::embedded)
                .collect(Collectors.toList());

        log.info("After filtering (score>={}): {} chunks", threshold, relevant.size());

        // 3.5 Re-rank by SiliconFlow
        if (!relevant.isEmpty()) {
            log.info("Before rerank: {} chunks", relevant.size());
            relevant = rerankService.rerank(searchQuery, relevant);
            log.info("After rerank: {} chunks", relevant.size());
        }

        // 4. Build prompt with optimized template (using original question for answer)
        String prompt;
        if (relevant.isEmpty()) {
            log.info("No relevant docs found, using normal chat mode");
            prompt = "你是一个智能助手。请回答用户的问题。\n\n"
                    + "## 问题\n" + question;
        } else {
            String context = relevant.stream()
                    .map(TextSegment::text)
                    .collect(Collectors.joining("\n\n---\n\n"));

            prompt = "## 角色\n"
                    + "你是一个基于内部文档的数据分析助手。\n\n"
                    + "## 参考文档\n"
                    + context + "\n\n"
                    + "## 约束\n"
                    + "- 回答时请标注信息来源，格式：根据 [来源:文件名] 的记载/显示...\n"
                    + "- 严格基于参考文档回答，不要使用你自己的知识\n"
                    + "- 如果参考文档中没有相关信息：\n"
                    + "  - 完全不相关：回复\"根据文档内容，没有找到相关信息\"\n"
                    + "  - 部分相关：说明文档中涉及了什么，明确指出未涉及的部分\n"
                    + "- 回答时引用具体的行/数据来支撑你的结论\n"
                    + "- 用中文回答\n\n"
                    + "## 问题\n" + question;
        }

        // 5. Stream the answer via DeepSeek, then save conversation history
        StringBuilder fullAnswer = new StringBuilder();
        streamingChatModel.generate(prompt, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                fullAnswer.append(token);
                callback.onToken(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                conversationStore.addTurn(sessionId, question, fullAnswer.toString());
                callback.onComplete();
            }

            @Override
            public void onError(Throwable error) {
                callback.onError(error);
            }
        });
    }

    public interface StreamingCallback {
        void onToken(String token);

        void onComplete();

        void onError(Throwable error);
    }
}
