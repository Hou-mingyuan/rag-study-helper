package com.rag.studyhelper.service;

import com.rag.studyhelper.model.ChatMessage;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
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

    public void streamAnswer(String sessionId, String question, StreamingResponseHandler<AiMessage> callback) {
        // 获取历史上下文
        List<ChatMessage> history = conversationStore.getHistory(sessionId);
        // 提问小于 5 个字，进行问题重写，为了使 RAG 检索更准确
        // 比如 RAG 场景下 你通过 《如何学习JAVA》 这个文档去检索（如果不了解向量数据库和embedding就先别管，只看问题）
        // 你第一次问："java 要学什么框架"
        // embedding 根据 "java 要学什么框架" 转成向量去检索到了相应的内容再放进 prompt 喂给 LLM
        // LLM 根据文档说："springboot"
        // 你第二次问："他有什么好处"
        // 我们可以一眼就看出这里的他指的是 springboot ，但是 embedding 模型不知道
        // embedding 只是把你输入的 "他有什么好处" 转换成向量去向量数据库查询，所以查出来的根本就不是你想要的文档内容
        // 这时 LLM 就不会根据文档去生成你想要的内容了
        String searchQuery = queryRewriteService.rewrite(question, history, 5);
        if (!searchQuery.equals(question)) {
            log.info("Search query rewritten: \"{}\" → \"{}\"", question, searchQuery);
        }

        // embedding（向量嵌入模型）根据你的问题转换成向量
        Embedding questionEmbedding = embeddingModel.embed(searchQuery).content();

//        todo 查询 rag 过滤 仅 chroma 和 milvus 使用 这里只是轻量的学习架构没有，引入用户和权限表啥的，所以我只讲实现思路
//        List<Long> accessibleDocIds = documentAccessService.getAccessibleDocIds(currentUserId);
//
//        // 构建过滤条件：公开的 OR 在我能看的私有文档列表里
//        Filter filter = MetadataFilterBuilder.metadataKey("visibility").isEqualTo("public");
//        if (!accessibleDocIds.isEmpty()) {
//            filter = filter.or(
//                    MetadataFilterBuilder.metadataKey("document_id").isIn(accessibleDocIds)
//            );
//        }
//        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
//                .queryEmbedding(questionEmbedding)
//                .maxResults(20)
//                .filter(filter)
//                .build();

        // 查询 向量数据库 找出前 20 个最相似的向量
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(20)
                .build();
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        // 查出来的向量结果
        List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();

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

        // 设置一个阈值，低于这个阈值的向量被过滤掉
        // 就是说你问 "java 是什么" 检索结果里是 "怎么做红烧肉" 这种跟 question 余弦相似度很低的 那么把这个检索丢给 LLM 有什么用呢
        double threshold = scoreThreshold;
        List<TextSegment> relevant = matches.stream()
                .filter(m -> m.score() >= threshold)
                .map(EmbeddingMatch::embedded)
                .collect(Collectors.toList());

        log.info("After filtering question={} score>={} chunks={}", question, threshold, relevant.size());

        // 检索召回 top 20，但真正有价值的可能只有其中 3-5 条，rerank 就是把最有用的排到最前面
        // 通过 rerank 找到最相关的 5 条（这里的 5 可以自定义）
        // 效果就是喂给 LLM 的上下文质量更高，回答更准，还省 token
        if (!relevant.isEmpty()) {
            relevant = rerankService.rerank(searchQuery, relevant, 5);
        }

        // 自定义 prompt 模板，如果检索结果为空，则使用普通对话模式
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

        // 使用 langChain 调用适配 OpenAI API 的模型生成答案
        StringBuilder fullAnswer = new StringBuilder();
        streamingChatModel.generate(prompt, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                fullAnswer.append(token);
                callback.onNext(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                TokenUsage usage = response.tokenUsage();
                if (usage != null) {
                    log.info("Token 用量 - 输入: {}, 输出: {}, 总和: {}",
                            usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount());
                }
                // 储存上下文
                conversationStore.addTurn(sessionId, question, fullAnswer.toString());
                callback.onComplete(response);
            }

            @Override
            public void onError(Throwable error) {
                callback.onError(error);
            }
        });
    }
}
