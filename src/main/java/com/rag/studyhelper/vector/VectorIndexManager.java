package com.rag.studyhelper.vector;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rag.studyhelper.mapper.DocumentChunksMapper;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.mapper.VectorIndexMetadataMapper;
import com.rag.studyhelper.model.DocumentChunks;
import com.rag.studyhelper.model.Documents;
import com.rag.studyhelper.model.VectorIndexMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class VectorIndexManager implements ApplicationRunner {

    private static final int SCHEMA_VERSION = 1;

    private final VectorIndexMetadataMapper metadataMapper;
    private final DocumentsMapper documentsMapper;
    private final DocumentChunksMapper chunksMapper;
    private final EmbeddingGateway embeddings;
    private final VectorStoreGateway vectors;
    private final String storeType;
    private final String collection;
    private final boolean rebuildOnStart;
    private final int batchSize;

    public VectorIndexManager(
            VectorIndexMetadataMapper metadataMapper,
            DocumentsMapper documentsMapper,
            DocumentChunksMapper chunksMapper,
            EmbeddingGateway embeddings,
            VectorStoreGateway vectors,
            @Value("${vector.store.type:in-memory}") String storeType,
            @Value("${app.vector.collection-name:rag_study_helper_v2}") String collection,
            @Value("${app.vector.rebuild-on-start:false}") boolean rebuildOnStart,
            @Value("${app.rag.embedding-batch-size:10}") int batchSize) {
        this.metadataMapper = metadataMapper;
        this.documentsMapper = documentsMapper;
        this.chunksMapper = chunksMapper;
        this.embeddings = embeddings;
        this.vectors = vectors;
        this.storeType = storeType;
        this.collection = collection;
        this.rebuildOnStart = rebuildOnStart;
        this.batchSize = Math.min(Math.max(batchSize, 1), 100);
    }

    @Override
    public void run(ApplicationArguments args) {
        VectorIndexMetadata metadata = findMetadata();
        long activeChunks = countActiveChunks();
        if (metadata == null) {
            if (!"in-memory".equals(storeType) && activeChunks > 0 && !rebuildOnStart) {
                throw new IllegalStateException("Vector index metadata is missing for a non-empty knowledge base. "
                        + "Use a new collection and set APP_VECTOR_REBUILD_ON_START=true.");
            }
            metadata = createMetadata("REBUILDING");
        }
        assertCompatible(metadata);

        if ("in-memory".equals(storeType) || rebuildOnStart || "REBUILDING".equals(metadata.getStatus())) {
            rebuild();
        }
    }

    public synchronized VectorRebuildReport rebuild() {
        VectorIndexMetadata metadata = findMetadata();
        if (metadata == null) {
            metadata = createMetadata("REBUILDING");
        }
        assertCompatible(metadata);
        updateStatus(metadata.getId(), "REBUILDING", metadata.getEntryCount(), null, null);

        List<ActiveChunk> active = activeChunks();
        long indexed = 0;
        try {
            for (int offset = 0; offset < active.size(); offset += batchSize) {
                int end = Math.min(offset + batchSize, active.size());
                List<ActiveChunk> batch = active.subList(offset, end);
                List<float[]> embedded = embeddings.embedAll(
                        batch.stream().map(item -> item.chunk().getChunkText()).toList());
                if (embedded.size() != batch.size()) {
                    throw new IllegalStateException("Embedding provider returned an incomplete rebuild batch");
                }
                List<VectorEntry> entries = new ArrayList<>(batch.size());
                for (int index = 0; index < batch.size(); index++) {
                    ActiveChunk item = batch.get(index);
                    entries.add(new VectorEntry(item.chunk().getVectorId(), embedded.get(index),
                            item.chunk().getChunkText(), metadata(item.document(), item.chunk())));
                }
                vectors.upsert(entries);
                indexed += entries.size();
            }
            LocalDateTime completedAt = LocalDateTime.now();
            updateStatus(metadata.getId(), "READY", indexed, null, completedAt);
            return report(indexed, active.size(), "READY");
        } catch (RuntimeException error) {
            updateStatus(metadata.getId(), "FAILED", indexed, abbreviate(error.getMessage()), null);
            throw error;
        }
    }

    public VectorRebuildReport status() {
        VectorIndexMetadata metadata = findMetadata();
        if (metadata == null) {
            return report(0, countActiveChunks(), "UNINITIALIZED");
        }
        return report(metadata.getEntryCount() == null ? 0 : metadata.getEntryCount(),
                countActiveChunks(), metadata.getStatus());
    }

    /**
     * Refreshes the durable count of the DB-authoritative active chunks after a
     * successful ingestion or deletion. This does not change compatibility or
     * rebuild status fields.
     */
    public synchronized void refreshEntryCount() {
        VectorIndexMetadata metadata = findMetadata();
        if (metadata == null) {
            return;
        }
        metadataMapper.update(null, Wrappers.<VectorIndexMetadata>lambdaUpdate()
                .eq(VectorIndexMetadata::getId, metadata.getId())
                .set(VectorIndexMetadata::getEntryCount, countActiveChunks())
                .set(VectorIndexMetadata::getUpdateTime, LocalDateTime.now()));
    }

    private void assertCompatible(VectorIndexMetadata metadata) {
        boolean compatible = metadata.getSchemaVersion() != null
                && metadata.getSchemaVersion() == SCHEMA_VERSION
                && metadata.getDimensionValue() != null
                && embeddings.dimension() == metadata.getDimensionValue()
                && embeddings.modelName() != null
                && embeddings.modelName().equals(metadata.getEmbeddingModel());
        if (compatible) {
            return;
        }
        updateStatus(metadata.getId(), "MISMATCH", metadata.getEntryCount(),
                "Configured embedding model or dimension differs from the collection metadata", null);
        throw new IllegalStateException("Vector index mismatch for collection '" + collection
                + "': stored model=" + metadata.getEmbeddingModel()
                + ", stored dimension=" + metadata.getDimensionValue()
                + ", configured model=" + embeddings.modelName()
                + ", configured dimension=" + embeddings.dimension()
                + ". Select a new collection and rebuild; the existing collection is not modified.");
    }

    private VectorIndexMetadata createMetadata(String status) {
        VectorIndexMetadata metadata = new VectorIndexMetadata();
        metadata.setStoreType(storeType);
        metadata.setCollectionName(collection);
        metadata.setSchemaVersion(SCHEMA_VERSION);
        metadata.setEmbeddingModel(embeddings.modelName());
        metadata.setDimensionValue(embeddings.dimension());
        metadata.setStatus(status);
        metadata.setEntryCount(0L);
        try {
            metadataMapper.insert(metadata);
            return metadata;
        } catch (DuplicateKeyException race) {
            VectorIndexMetadata existing = findMetadata();
            if (existing != null) {
                return existing;
            }
            throw race;
        }
    }

    private VectorIndexMetadata findMetadata() {
        return metadataMapper.selectOne(Wrappers.<VectorIndexMetadata>lambdaQuery()
                .eq(VectorIndexMetadata::getStoreType, storeType)
                .eq(VectorIndexMetadata::getCollectionName, collection));
    }

    private List<ActiveChunk> activeChunks() {
        List<Documents> documents = documentsMapper.selectList(Wrappers.<Documents>lambdaQuery()
                .eq(Documents::getStatus, "READY"));
        if (documents.isEmpty()) {
            return List.of();
        }
        Map<Long, Documents> byId = new HashMap<>();
        documents.forEach(document -> byId.put(document.getId(), document));
        List<DocumentChunks> chunks = chunksMapper.selectList(Wrappers.<DocumentChunks>lambdaQuery()
                .eq(DocumentChunks::getStatus, "READY")
                .in(DocumentChunks::getDocumentId, byId.keySet())
                .orderByAsc(DocumentChunks::getDocumentId, DocumentChunks::getChunkIndex));
        List<ActiveChunk> active = new ArrayList<>();
        for (DocumentChunks chunk : chunks) {
            Documents document = byId.get(chunk.getDocumentId());
            if (document != null && document.getCurrentVersion() != null
                    && document.getCurrentVersion().equals(chunk.getDocumentVersion())) {
                active.add(new ActiveChunk(document, chunk));
            }
        }
        return active;
    }

    private long countActiveChunks() {
        return activeChunks().size();
    }

    private Map<String, Object> metadata(Documents document, DocumentChunks chunk) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("spaceId", document.getSpaceId());
        values.put("documentId", document.getId());
        values.put("documentVersion", chunk.getDocumentVersion());
        values.put("chunkId", chunk.getId());
        values.put("chunkIndex", chunk.getChunkIndex());
        values.put("documentName", document.getDocumentName());
        values.put("source", document.getSource());
        if (chunk.getSectionTitle() != null) {
            values.put("section", chunk.getSectionTitle());
        }
        if (chunk.getPageNumber() != null) {
            values.put("page", chunk.getPageNumber());
        }
        return values;
    }

    private void updateStatus(Long id, String status, Long entryCount,
                              String error, LocalDateTime rebuiltAt) {
        LocalDateTime updatedAt = LocalDateTime.now();
        metadataMapper.update(null, Wrappers.<VectorIndexMetadata>lambdaUpdate()
                .eq(VectorIndexMetadata::getId, id)
                .set(VectorIndexMetadata::getStatus, status)
                .set(VectorIndexMetadata::getEntryCount, entryCount == null ? 0 : entryCount)
                .set(VectorIndexMetadata::getLastError, error)
                .set(VectorIndexMetadata::getLastRebuildTime, rebuiltAt)
                .set(VectorIndexMetadata::getUpdateTime, updatedAt));
    }

    private VectorRebuildReport report(long indexed, long active, String status) {
        return new VectorRebuildReport(storeType, collection, embeddings.modelName(),
                embeddings.dimension(), active, indexed, status);
    }

    private static String abbreviate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }

    private record ActiveChunk(Documents document, DocumentChunks chunk) {
    }
}
