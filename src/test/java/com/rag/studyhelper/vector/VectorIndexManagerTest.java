package com.rag.studyhelper.vector;

import com.rag.studyhelper.mapper.DocumentChunksMapper;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.mapper.VectorIndexMetadataMapper;
import com.rag.studyhelper.model.DocumentChunks;
import com.rag.studyhelper.model.Documents;
import com.rag.studyhelper.model.VectorIndexMetadata;
import com.rag.studyhelper.support.MybatisMetadata;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorIndexManagerTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisMetadata.initialize(VectorIndexMetadata.class, Documents.class, DocumentChunks.class);
    }

    @Mock
    private VectorIndexMetadataMapper metadataMapper;
    @Mock
    private DocumentsMapper documentsMapper;
    @Mock
    private DocumentChunksMapper chunksMapper;
    @Mock
    private EmbeddingGateway embeddings;
    @Mock
    private VectorStoreGateway vectors;

    @Test
    void rebuildIndexesOnlyTheActiveDocumentVersionWithStableIds() {
        VectorIndexMetadata metadata = metadata(256, "mock-hash-256");
        when(metadataMapper.selectOne(any())).thenReturn(metadata);
        when(embeddings.dimension()).thenReturn(256);
        when(embeddings.modelName()).thenReturn("mock-hash-256");

        Documents document = new Documents();
        document.setId(7L);
        document.setSpaceId(3L);
        document.setStatus("READY");
        document.setCurrentVersion(2);
        document.setDocumentName("guide.md");
        document.setSource("UPLOAD");
        when(documentsMapper.selectList(any())).thenReturn(List.of(document));

        DocumentChunks current = chunk(21L, 2, "vector-current", "current text");
        DocumentChunks stale = chunk(20L, 1, "vector-stale", "stale text");
        when(chunksMapper.selectList(any())).thenReturn(List.of(stale, current));
        when(embeddings.embedAll(List.of("current text")))
                .thenReturn(List.of(new float[256]));

        VectorRebuildReport report = manager().rebuild();

        assertEquals(1, report.activeChunks());
        assertEquals(1, report.indexedChunks());
        ArgumentCaptor<List<VectorEntry>> entries = ArgumentCaptor.forClass(List.class);
        verify(vectors).upsert(entries.capture());
        assertEquals("vector-current", entries.getValue().get(0).id());
        assertEquals(3L, entries.getValue().get(0).metadata().get("spaceId"));
    }

    @Test
    void mismatchFailsBeforeAnyEmbeddingOrVectorWrite() {
        when(metadataMapper.selectOne(any())).thenReturn(metadata(1024, "old-model"));
        when(embeddings.dimension()).thenReturn(256);
        when(embeddings.modelName()).thenReturn("mock-hash-256");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> manager().rebuild());

        assertEquals(true, error.getMessage().contains("Select a new collection"));
        verify(embeddings, never()).embedAll(anyList());
        verify(vectors, never()).upsert(anyList());
    }

    private VectorIndexManager manager() {
        return new VectorIndexManager(metadataMapper, documentsMapper, chunksMapper,
                embeddings, vectors, "in-memory", "test-collection", false, 10);
    }

    private VectorIndexMetadata metadata(int dimension, String model) {
        VectorIndexMetadata metadata = new VectorIndexMetadata();
        metadata.setId(1L);
        metadata.setStoreType("in-memory");
        metadata.setCollectionName("test-collection");
        metadata.setSchemaVersion(1);
        metadata.setEmbeddingModel(model);
        metadata.setDimensionValue(dimension);
        metadata.setStatus("READY");
        metadata.setEntryCount(0L);
        return metadata;
    }

    private DocumentChunks chunk(long id, int version, String vectorId, String text) {
        DocumentChunks chunk = new DocumentChunks();
        chunk.setId(id);
        chunk.setDocumentId(7L);
        chunk.setSpaceId(3L);
        chunk.setDocumentVersion(version);
        chunk.setChunkIndex(0);
        chunk.setVectorId(vectorId);
        chunk.setChunkText(text);
        chunk.setStatus("READY");
        return chunk;
    }
}
