package com.rag.studyhelper.service;

import com.rag.studyhelper.ingestion.ActivationResult;
import com.rag.studyhelper.ingestion.IndexStage;
import com.rag.studyhelper.ingestion.IngestionLockCoordinator;
import com.rag.studyhelper.ingestion.IngestionPersistence;
import com.rag.studyhelper.model.DocumentChunks;
import com.rag.studyhelper.model.DocumentInfo;
import com.rag.studyhelper.model.DocumentVersion;
import com.rag.studyhelper.model.Documents;
import com.rag.studyhelper.vector.EmbeddingGateway;
import com.rag.studyhelper.vector.VectorEntry;
import com.rag.studyhelper.vector.VectorIndexManager;
import com.rag.studyhelper.vector.VectorStoreGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import com.rag.studyhelper.ingestion.ChunkDraft;
import com.rag.studyhelper.ingestion.DocumentDescriptor;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionConsistencyTest {

    @Mock
    private IngestionPersistence persistence;
    @Mock
    private IngestionLockCoordinator ingestionLocks;
    @Mock
    private EmbeddingGateway embeddings;
    @Mock
    private VectorStoreGateway vectors;
    @Mock
    private VectorIndexManager vectorIndexManager;
    @Mock
    private com.rag.studyhelper.mapper.DocumentsMapper documentsMapper;
    @Mock
    private com.rag.studyhelper.mapper.DocumentChunksMapper chunksMapper;
    @Mock
    private KnowledgeSpaceService spaces;

    private DocumentIngestionService service;

    @BeforeEach
    void setUp() {
        service = new DocumentIngestionService(
                persistence, ingestionLocks, embeddings, vectors, vectorIndexManager,
                documentsMapper, chunksMapper, spaces);
        ReflectionTestUtils.setField(service, "embeddingBatchSize", 10);
    }

    @Test
    void embeddingFailureNeverActivatesAndMarksStagedVersionFailed() {
        IndexStage stage = stage(false);
        when(persistence.stage(any(), anyList())).thenReturn(stage);
        when(embeddings.embedAll(anyList())).thenThrow(new IllegalStateException("provider unavailable"));

        assertThrows(IOException.class, () -> upload("failure.md", "# Topic\ncontent"));

        verify(vectors).delete(List.of());
        verify(persistence).fail(eq(stage), eq("provider unavailable"), eq(false));
        verify(persistence, never()).activate(any());
    }

    @Test
    void partialVectorWriteFailureDeletesEveryAttemptedDeterministicId() {
        IndexStage stage = stage(false);
        when(persistence.stage(any(), anyList())).thenReturn(stage);
        when(embeddings.embedAll(anyList())).thenReturn(List.of(new float[]{1f, 0f}));
        doThrow(new IllegalStateException("partial write")).when(vectors).upsert(anyList());

        assertThrows(IOException.class, () -> upload("partial.md", "content"));

        verify(vectors).delete(List.of("vector-1"));
        verify(persistence).fail(eq(stage), eq("partial write"), eq(false));
        verify(persistence, never()).activate(any());
    }

    @Test
    void staleCleanupFailureIsQueuedAfterNewVersionActivation() throws Exception {
        IndexStage stage = stage(false);
        when(persistence.stage(any(), anyList())).thenReturn(stage);
        when(embeddings.embedAll(anyList())).thenReturn(List.of(new float[]{1f, 0f}));
        when(persistence.activate(stage)).thenReturn(
                new ActivationResult(new DocumentInfo(7L, "updated.md", 1), List.of("old-vector")));
        doThrow(new IllegalStateException("delete unavailable"))
                .when(vectors).delete(List.of("old-vector"));

        DocumentInfo result = upload("updated.md", "content");

        assertEquals(7L, result.getId());
        verify(persistence).enqueueDeletes(eq(1L), eq(7L), eq(List.of("old-vector")), any());
        verify(vectorIndexManager).refreshEntryCount();
        verify(persistence, never()).fail(any(), any(), eq(false));
    }

    @Test
    void duplicateUploadReturnsExistingDocumentWithoutExternalWrites() throws Exception {
        IndexStage duplicate = stage(true);
        when(persistence.stage(any(), anyList())).thenReturn(duplicate);

        DocumentInfo result = upload("same.md", "same content");

        assertEquals(7L, result.getId());
        verify(embeddings, never()).embedAll(anyList());
        verify(vectors, never()).upsert(anyList());
        verify(persistence, never()).activate(any());
    }

    @Test
    void chunkingPreservesOverlapAndSectionMetadataForLongMarkdown() throws Exception {
        when(persistence.stage(any(), anyList())).thenReturn(stage(true));
        String longText = "# Consistency\n" + "vector consistency and recovery. ".repeat(900);

        upload("long.md", longText);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChunkDraft>> drafts = ArgumentCaptor.forClass(List.class);
        verify(persistence).stage(any(DocumentDescriptor.class), drafts.capture());
        assertTrue(drafts.getValue().size() > 1);
        assertEquals("Consistency", drafts.getValue().get(0).sectionTitle());
        for (int index = 1; index < drafts.getValue().size(); index++) {
            assertTrue(drafts.getValue().get(index).startOffset()
                    <= drafts.getValue().get(index - 1).endOffset());
        }
    }

    @Test
    void inputAndExtractedTextLimitsFailBeforeAnyDatabaseStage() {
        ReflectionTestUtils.setField(service, "maxDocumentBytes", 3L);
        assertThrows(IllegalArgumentException.class,
                () -> upload("large.md", "four"));

        ReflectionTestUtils.setField(service, "maxDocumentBytes", 1024L);
        ReflectionTestUtils.setField(service, "maxExtractedCharacters", 3);
        assertThrows(IllegalArgumentException.class,
                () -> upload("expanded.md", "four"));

        verify(persistence, never()).stage(any(), anyList());
    }

    private DocumentInfo upload(String name, String text) throws Exception {
        return service.ingestDocument(1L, name, "text/markdown",
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private IndexStage stage(boolean duplicate) {
        Documents document = new Documents();
        document.setId(7L);
        document.setSpaceId(1L);
        document.setDocumentName("same.md");
        document.setChunkCount(1);
        document.setStatus("READY");
        document.setCurrentVersion(1);

        if (duplicate) {
            return IndexStage.duplicate(document);
        }

        DocumentVersion version = new DocumentVersion();
        version.setId(11L);
        version.setVersionNumber(2);

        DocumentChunks chunk = new DocumentChunks();
        chunk.setId(21L);
        chunk.setDocumentId(7L);
        chunk.setDocumentVersion(2);
        chunk.setChunkIndex(0);
        chunk.setChunkText("content");
        chunk.setVectorId("vector-1");
        return new IndexStage(document, version, List.of(chunk), false);
    }
}
