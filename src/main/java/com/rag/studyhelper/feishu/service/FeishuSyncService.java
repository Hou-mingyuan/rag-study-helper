package com.rag.studyhelper.feishu.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rag.studyhelper.feishu.client.FeishuClient;
import com.rag.studyhelper.feishu.client.WikiNode;
import com.rag.studyhelper.mapper.DocumentChunksMapper;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.model.DocumentChunks;
import com.rag.studyhelper.model.Documents;
import com.rag.studyhelper.service.DocumentIngestionService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.stream.Collectors;

public class FeishuSyncService {

    private static final Logger log = LoggerFactory.getLogger(FeishuSyncService.class);

    private final FeishuClient feishuClient;
    private final DocumentIngestionService ingestionService;
    private final String wikiId;

    @Autowired
    private DocumentsMapper documentsMapper;

    @Autowired
    private DocumentChunksMapper documentChunksMapper;

    @Autowired
    private EmbeddingStore<TextSegment> embeddingStore;

    public FeishuSyncService(FeishuClient feishuClient,
                             DocumentIngestionService ingestionService, String wikiId) {
        if (wikiId == null || wikiId.trim().isEmpty()) {
            throw new IllegalArgumentException("app.feishu.wiki-id 未配置，可通过 FeishuClient.listSpaces() 获取可用 space_id");
        }
        this.feishuClient = feishuClient;
        this.ingestionService = ingestionService;
        this.wikiId = wikiId.trim();
    }

//    @PostConstruct
//    public void init(){
//        syncWiki();
//    }

    @Scheduled(cron = "${app.feishu.cron}")
    public void syncWiki() {
        log.info("Starting Feishu wiki sync for space: {}", wikiId);
        try {
            List<WikiNode> nodes = feishuClient.getWikiNodeTree(wikiId);
            log.info("Found {} nodes in wiki", nodes.size());

            int synced = 0, skipped = 0, failed = 0;

            for (WikiNode node : nodes) {
                String objType = node.getObjType();
                String nodeToken = node.getNodeToken();
                long updateTime = node.getUpdateTime();

                Documents doc = documentsMapper.selectOne(
                        Wrappers.<Documents>lambdaQuery()
                                .eq(Documents::getFeishuNodeToken, nodeToken)
                );
                if (doc != null && doc.getFeishuUpdateTime() != null
                        && doc.getFeishuUpdateTime() == updateTime) {
                    skipped++;
                    continue;
                }

                try {
                    String content;
                    String fileName;
                    switch (objType) {
                        case "doc":
                        case "docx":
                            content = feishuClient.getDocumentContent(node.getObjToken());
                            fileName = node.getNodeTitle();
                            break;
                        case "sheet":
                            content = feishuClient.getSheetContent(node.getObjToken());
                            fileName = node.getNodeTitle() + "_表格";
                            break;
                        case "bitable":
                            content = feishuClient.getBitableContent(node.getObjToken());
                            fileName = node.getNodeTitle() + "_多维表格";
                            break;
                        default:
                            skipped++;
                            continue;
                    }

                    // 如果是更新，先删旧向量和映射记录
                    Documents existing = documentsMapper.selectOne(
                            Wrappers.<Documents>lambdaQuery()
                                    .eq(Documents::getFeishuNodeToken, nodeToken)
                    );
                    if (existing != null) {
                        List<DocumentChunks> oldChunks = documentChunksMapper.selectList(
                                Wrappers.<DocumentChunks>lambdaQuery()
                                        .eq(DocumentChunks::getDocumentId, existing.getId())
                        );
                        for (DocumentChunks chunk : oldChunks) {
                            try {
                                embeddingStore.remove(chunk.getVectorId());
                            } catch (Exception e) {
                                log.warn("Failed to remove old vector {}: {}", chunk.getVectorId(), e.getMessage());
                            }
                        }
                        documentChunksMapper.delete(
                                Wrappers.<DocumentChunks>lambdaQuery()
                                        .eq(DocumentChunks::getDocumentId, existing.getId())
                        );
                        documentsMapper.deleteById(existing.getId());
                    }

                    ingestionService.ingestFeishuDocument(fileName, content, nodeToken, updateTime, objType);
                    synced++;
                    log.info("  Synced: {} ({})", node.getNodeTitle(), nodeToken);
                } catch (Exception e) {
                    log.error("  Failed to sync node: {} ({})", node.getNodeTitle(), nodeToken, e);
                    failed++;
                }
            }

            // 清理远程已删除的文档
            // 这里的逻辑是 第一次飞书给了 A、B、C 入库，删了C，第二次只有 A、B 就去数据库中和向量库中删 C
            // MySQL 查出本地多出的记录，只遍历需要删除的
            List<String> remoteTokens = nodes.stream()
                    .map(WikiNode::getNodeToken)
                    .collect(Collectors.toList());
            if (!remoteTokens.isEmpty()) {
                List<Documents> toRemove = documentsMapper.selectList(
                        Wrappers.<Documents>lambdaQuery()
                                .isNotNull(Documents::getFeishuNodeToken)
                                .notIn(Documents::getFeishuNodeToken, remoteTokens)
                );
                for (Documents removed : toRemove) {
                    log.info("Document removed remotely, cleaning up: {} ({})", removed.getDocumentName(), removed.getFeishuNodeToken());
                    List<DocumentChunks> chunks = documentChunksMapper.selectList(
                            Wrappers.<DocumentChunks>lambdaQuery()
                                    .eq(DocumentChunks::getDocumentId, removed.getId())
                    );
                    for (DocumentChunks chunk : chunks) {
                        try {
                            embeddingStore.remove(chunk.getVectorId());
                        } catch (Exception e) {
                            log.warn("Failed to remove vector {}: {}", chunk.getVectorId(), e.getMessage());
                        }
                    }
                    documentChunksMapper.delete(
                            Wrappers.<DocumentChunks>lambdaQuery()
                                    .eq(DocumentChunks::getDocumentId, removed.getId())
                    );
                    documentsMapper.deleteById(removed.getId());
                }
            }

            log.info("Feishu wiki sync complete: synced={}, skipped={}, failed={}",
                    synced, skipped, failed);
        } catch (Exception e) {
            log.error("Feishu wiki sync failed", e);
        }
    }
}
