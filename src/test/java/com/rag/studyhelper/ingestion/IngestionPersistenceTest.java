package com.rag.studyhelper.ingestion;

import com.rag.studyhelper.mapper.DocumentChunksMapper;
import com.rag.studyhelper.mapper.DocumentVersionMapper;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.mapper.VectorReconciliationMapper;
import com.rag.studyhelper.model.DocumentChunks;
import com.rag.studyhelper.model.DocumentVersion;
import com.rag.studyhelper.model.Documents;
import com.rag.studyhelper.model.VectorReconciliation;
import com.rag.studyhelper.support.MybatisMetadata;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionPersistenceTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisMetadata.initialize(Documents.class, DocumentVersion.class,
                DocumentChunks.class, VectorReconciliation.class);
    }

    @Mock private DocumentsMapper documents;
    @Mock private DocumentVersionMapper versions;
    @Mock private DocumentChunksMapper chunks;
    @Mock private VectorReconciliationMapper reconciliation;

    @Test
    void sameFileNameWithChangedHashCreatesANewVersion() {
        Documents existing = existing("old-hash");
        when(documents.selectOne(any())).thenReturn(null, existing);
        when(documents.update(any(), any())).thenReturn(1);
        DocumentVersion latest = new DocumentVersion();
        latest.setVersionNumber(1);
        when(versions.selectOne(any())).thenReturn(latest);
        IngestionPersistence persistence =
                new IngestionPersistence(documents, versions, chunks, reconciliation);

        IndexStage stage = persistence.stage(descriptor("new-hash"), List.of(draft()));

        assertFalse(stage.duplicate());
        assertEquals(2, stage.version().getVersionNumber());
        assertEquals("PENDING", stage.chunks().get(0).getStatus());
        verify(versions).insert(any(DocumentVersion.class));
        verify(chunks).insert(any(DocumentChunks.class));
    }

    @Test
    void equalContentHashReturnsExistingReadyDocumentWithoutStaging() {
        Documents existing = existing("same-hash");
        when(documents.selectOne(any())).thenReturn(existing);
        IngestionPersistence persistence =
                new IngestionPersistence(documents, versions, chunks, reconciliation);

        IndexStage stage = persistence.stage(descriptor("same-hash"), List.of(draft()));

        assertTrue(stage.duplicate());
        verify(documents, never()).update(any(), any());
        verify(versions, never()).insert(any(DocumentVersion.class));
        verify(chunks, never()).insert(any(DocumentChunks.class));
    }

    private Documents existing(String hash) {
        Documents document = new Documents();
        document.setId(7L);
        document.setSpaceId(3L);
        document.setDocumentName("guide.md");
        document.setSource("UPLOAD");
        document.setStatus("READY");
        document.setCurrentVersion(1);
        document.setContentHash(hash);
        document.setRowVersion(1L);
        return document;
    }

    private DocumentDescriptor descriptor(String hash) {
        return new DocumentDescriptor(3L, "guide.md", "md", "text/markdown",
                "UPLOAD", hash, 42L, null,
                null, null, null, 0L, "local");
    }

    private ChunkDraft draft() {
        return new ChunkDraft(0, "new content", "chunk-hash", "Topic",
                null, 0, 11, 3);
    }
}
