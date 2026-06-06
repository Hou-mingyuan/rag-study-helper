package com.rag.studyhelper.feishu.service;

import com.rag.studyhelper.feishu.client.FeishuClient;
import com.rag.studyhelper.feishu.client.WikiNode;
import com.rag.studyhelper.mapper.DocumentChunksMapper;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.model.DocumentChunks;
import com.rag.studyhelper.model.Documents;
import com.rag.studyhelper.service.DocumentIngestionService;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeishuSyncServiceTest {

    @Mock
    private FeishuClient feishuClient;
    @Mock
    private DocumentIngestionService ingestionService;
    @Mock
    private DocumentsMapper documentsMapper;
    @Mock
    private DocumentChunksMapper documentChunksMapper;
    @Mock
    private EmbeddingStore embeddingStore;

    private FeishuSyncService syncService;

    @BeforeEach
    void setUp() {
        syncService = new FeishuSyncService(feishuClient, ingestionService, "123456");
        ReflectionTestUtils.setField(syncService, "documentsMapper", documentsMapper);
        ReflectionTestUtils.setField(syncService, "documentChunksMapper", documentChunksMapper);
        ReflectionTestUtils.setField(syncService, "embeddingStore", embeddingStore);
    }

    @Test
    void shouldSkipUnchangedDocuments() throws Exception {
        WikiNode node = createNode("n1", "Doc1", "o1", 100L);
        when(feishuClient.getWikiNodeTree("123456")).thenReturn(Collections.singletonList(node));
        when(documentsMapper.selectOne(any()))
                .thenReturn(createDocRecord("n1", 100L));

        syncService.syncWiki();

        verify(feishuClient, never()).getDocumentContent(anyString());
        verify(ingestionService, never()).ingestFeishuDocument(anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void shouldIngestNewDocuments() throws Exception {
        WikiNode node = createNode("n1", "Doc1", "o1", 100L);
        when(feishuClient.getWikiNodeTree("123456")).thenReturn(Collections.singletonList(node));
        when(documentsMapper.selectOne(any())).thenReturn(null);
        when(feishuClient.getDocumentContent("o1")).thenReturn("Hello content");

        syncService.syncWiki();

        verify(ingestionService, times(1))
                .ingestFeishuDocument("Doc1", "Hello content", "n1", 100L, "doc");
    }

    @Test
    void shouldUpdateExistingDocuments() throws Exception {
        WikiNode node = createNode("n1", "Doc1", "o1", 200L);
        when(feishuClient.getWikiNodeTree("123456")).thenReturn(Collections.singletonList(node));
        // First call: check if changed (old updateTime ≠ new)
        when(documentsMapper.selectOne(any()))
                .thenReturn(createDocRecord("n1", 100L));
        when(feishuClient.getDocumentContent("o1")).thenReturn("Updated content");
        when(documentChunksMapper.selectList(any()))
                .thenReturn(Arrays.asList(createChunk(1L, "v1", 0), createChunk(1L, "v2", 1)));

        syncService.syncWiki();

        // Should delete old vectors
        verify(embeddingStore, times(2)).remove(anyString());
        // Should delete old chunks mapping
        verify(documentChunksMapper, times(1)).delete(any());
        // Should delete old document record
        verify(documentsMapper, times(1)).deleteById(1L);
        // Should re-ingest
        verify(ingestionService, times(1))
                .ingestFeishuDocument("Doc1", "Updated content", "n1", 200L, "doc");
    }

    @Test
    void shouldSkipNonDocNodes() throws Exception {
        WikiNode folder = createNode("f1", "Folder", "", 0L);
        folder.setObjType("");
        WikiNode sheet = createNode("s1", "Sheet1", "so1", 100L);
        sheet.setObjType("sheet");
        when(feishuClient.getWikiNodeTree("123456")).thenReturn(Arrays.asList(folder, sheet));
        when(documentsMapper.selectOne(any())).thenReturn(null);

        syncService.syncWiki();

        verify(feishuClient, never()).getDocumentContent(anyString());
        verify(ingestionService, never()).ingestFeishuDocument(anyString(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void shouldContinueOnSingleNodeFailure() throws Exception {
        WikiNode node1 = createNode("n1", "Doc1", "o1", 100L);
        WikiNode node2 = createNode("n2", "Doc2", "o2", 200L);
        when(feishuClient.getWikiNodeTree("123456")).thenReturn(Arrays.asList(node1, node2));
        when(documentsMapper.selectOne(any())).thenReturn(null);
        when(feishuClient.getDocumentContent("o1")).thenThrow(new RuntimeException("API error"));
        when(feishuClient.getDocumentContent("o2")).thenReturn("Doc2 content");

        syncService.syncWiki();

        verify(ingestionService, times(1))
                .ingestFeishuDocument("Doc2", "Doc2 content", "n2", 200L, "doc");
    }

    @Test
    void shouldCleanupRemotelyDeletedDocuments() throws Exception {
        WikiNode node = createNode("n1", "Doc1", "o1", 100L);
        when(feishuClient.getWikiNodeTree("123456")).thenReturn(Collections.singletonList(node));
        when(documentsMapper.selectOne(any())).thenReturn(null);
        when(feishuClient.getDocumentContent("o1")).thenReturn("Content");

        Documents remoteDeleted = createDocRecord("n2", 0L);
        remoteDeleted.setDocumentName("DeletedDoc");
        when(documentsMapper.selectList(any()))
                .thenReturn(Collections.singletonList(remoteDeleted));
        when(documentChunksMapper.selectList(any()))
                .thenReturn(Collections.singletonList(createChunk(2L, "v3", 0)));

        syncService.syncWiki();

        verify(embeddingStore, times(1)).remove("v3");
        verify(documentChunksMapper, atLeast(1)).delete(any());
        verify(documentsMapper, atLeast(1)).deleteById(2L);
        verify(ingestionService, times(1))
                .ingestFeishuDocument("Doc1", "Content", "n1", 100L, "doc");
    }

    private Documents createDocRecord(String nodeToken, long updateTime) {
        Documents doc = new Documents();
        doc.setId(nodeToken.equals("n1") ? 1L : 2L);
        doc.setFeishuNodeToken(nodeToken);
        doc.setFeishuUpdateTime(updateTime);
        return doc;
    }

    private DocumentChunks createChunk(Long documentId, String vectorId, int index) {
        DocumentChunks chunk = new DocumentChunks();
        chunk.setId((long) index);
        chunk.setDocumentId(documentId);
        chunk.setVectorId(vectorId);
        chunk.setChunkIndex(index);
        return chunk;
    }

    private WikiNode createNode(String token, String title, String objToken, long updateTime) {
        WikiNode node = new WikiNode();
        node.setNodeToken(token);
        node.setNodeTitle(title);
        node.setObjToken(objToken);
        node.setObjType("doc");
        node.setHasChild(false);
        node.setUpdateTime(updateTime);
        return node;
    }
}
