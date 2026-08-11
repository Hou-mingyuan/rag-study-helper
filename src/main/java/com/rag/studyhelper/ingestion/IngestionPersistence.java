package com.rag.studyhelper.ingestion;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rag.studyhelper.mapper.DocumentChunksMapper;
import com.rag.studyhelper.mapper.DocumentVersionMapper;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.mapper.VectorReconciliationMapper;
import com.rag.studyhelper.model.DocumentChunks;
import com.rag.studyhelper.model.DocumentInfo;
import com.rag.studyhelper.model.DocumentVersion;
import com.rag.studyhelper.model.Documents;
import com.rag.studyhelper.model.VectorReconciliation;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Service
public class IngestionPersistence {

    private final DocumentsMapper documentsMapper;
    private final DocumentVersionMapper versionsMapper;
    private final DocumentChunksMapper chunksMapper;
    private final VectorReconciliationMapper reconciliationMapper;

    public IngestionPersistence(DocumentsMapper documentsMapper,
                                DocumentVersionMapper versionsMapper,
                                DocumentChunksMapper chunksMapper,
                                VectorReconciliationMapper reconciliationMapper) {
        this.documentsMapper = documentsMapper;
        this.versionsMapper = versionsMapper;
        this.chunksMapper = chunksMapper;
        this.reconciliationMapper = reconciliationMapper;
    }

    @Transactional
    public IndexStage stage(DocumentDescriptor descriptor, List<ChunkDraft> drafts) {
        Documents document = findExisting(descriptor);
        if (isDuplicate(descriptor, document)) {
            return IndexStage.duplicate(document);
        }

        if (document == null) {
            document = new Documents();
            document.setSpaceId(descriptor.spaceId());
            document.setStatus("INDEXING");
            document.setCurrentVersion(0);
            document.setDocumentName(descriptor.documentName());
            document.setDocumentType(descriptor.documentType());
            document.setMimeType(descriptor.mimeType());
            document.setSource(descriptor.source());
            document.setContentHash(null);
            document.setFileSize(descriptor.fileSize());
            document.setOriginalPath(descriptor.originalPath());
            document.setChunkCount(0);
            document.setRemoteSpaceId(descriptor.remoteSpaceId());
            document.setFeishuNodeToken(descriptor.feishuNodeToken());
            document.setFeishuObjType(descriptor.feishuObjType());
            document.setFeishuUpdateTime(0L);
            document.setRemoteMissingCount(0);
            document.setCreator(descriptor.creator());
            document.setRowVersion(0L);
            documentsMapper.insert(document);
        }

        int nextVersion = nextVersion(document.getId());
        document.setPendingVersion(nextVersion);
        document.setLastError(null);
        int staged = documentsMapper.update(null, Wrappers.<Documents>lambdaUpdate()
                .eq(Documents::getId, document.getId())
                .set(Documents::getPendingVersion, nextVersion)
                .set(Documents::getLastError, null));
        if (staged != 1) {
            throw new IllegalStateException("Document staging state could not be persisted");
        }

        DocumentVersion version = new DocumentVersion();
        version.setSpaceId(descriptor.spaceId());
        version.setDocumentId(document.getId());
        version.setVersionNumber(nextVersion);
        version.setStatus("INDEXING");
        version.setDocumentName(descriptor.documentName());
        version.setDocumentType(descriptor.documentType());
        version.setMimeType(descriptor.mimeType());
        version.setSource(descriptor.source());
        version.setContentHash(descriptor.contentHash());
        version.setFileSize(descriptor.fileSize());
        version.setOriginalPath(descriptor.originalPath());
        version.setFeishuUpdateTime(descriptor.feishuUpdateTime());
        version.setChunkCount(drafts.size());
        versionsMapper.insert(version);

        List<DocumentChunks> chunks = new ArrayList<>(drafts.size());
        for (ChunkDraft draft : drafts) {
            DocumentChunks chunk = new DocumentChunks();
            chunk.setSpaceId(descriptor.spaceId());
            chunk.setDocumentId(document.getId());
            chunk.setDocumentVersion(nextVersion);
            chunk.setVectorId(vectorId(descriptor.spaceId(), document.getId(), nextVersion,
                    draft.index(), draft.contentHash()));
            chunk.setChunkIndex(draft.index());
            chunk.setChunkHash(draft.contentHash());
            chunk.setSectionTitle(draft.sectionTitle());
            chunk.setPageNumber(draft.pageNumber());
            chunk.setStartOffset(draft.startOffset());
            chunk.setEndOffset(draft.endOffset());
            chunk.setTokenCount(draft.tokenCount());
            chunk.setStatus("PENDING");
            chunk.setChunkText(draft.text());
            chunksMapper.insert(chunk);
            chunks.add(chunk);
        }
        return new IndexStage(document, version, chunks, false);
    }

    @Transactional
    public ActivationResult activate(IndexStage stage) {
        Documents document = documentsMapper.selectById(stage.document().getId());
        if (document == null || !stage.version().getVersionNumber().equals(document.getPendingVersion())) {
            throw new IllegalStateException("Document staging version is no longer current");
        }

        int oldVersion = document.getCurrentVersion() == null ? 0 : document.getCurrentVersion();
        List<String> staleIds = oldVersion <= 0 ? List.of() : chunksMapper.selectList(
                        Wrappers.<DocumentChunks>lambdaQuery()
                                .eq(DocumentChunks::getDocumentId, document.getId())
                                .eq(DocumentChunks::getDocumentVersion, oldVersion))
                .stream().map(DocumentChunks::getVectorId).toList();

        chunksMapper.update(null, Wrappers.<DocumentChunks>lambdaUpdate()
                .eq(DocumentChunks::getDocumentId, document.getId())
                .eq(DocumentChunks::getDocumentVersion, stage.version().getVersionNumber())
                .set(DocumentChunks::getStatus, "READY"));
        versionsMapper.update(null, Wrappers.<DocumentVersion>lambdaUpdate()
                .eq(DocumentVersion::getId, stage.version().getId())
                .set(DocumentVersion::getStatus, "READY")
                .set(DocumentVersion::getErrorMessage, null));

        if (oldVersion > 0) {
            chunksMapper.update(null, Wrappers.<DocumentChunks>lambdaUpdate()
                    .eq(DocumentChunks::getDocumentId, document.getId())
                    .eq(DocumentChunks::getDocumentVersion, oldVersion)
                    .set(DocumentChunks::getStatus, "STALE"));
            versionsMapper.update(null, Wrappers.<DocumentVersion>lambdaUpdate()
                    .eq(DocumentVersion::getDocumentId, document.getId())
                    .eq(DocumentVersion::getVersionNumber, oldVersion)
                    .set(DocumentVersion::getStatus, "STALE"));
        }

        DocumentVersion version = stage.version();
        long nextRowVersion = (document.getRowVersion() == null ? 0 : document.getRowVersion()) + 1;
        int activated = documentsMapper.update(null, Wrappers.<Documents>lambdaUpdate()
                .eq(Documents::getId, document.getId())
                .eq(Documents::getPendingVersion, version.getVersionNumber())
                .set(Documents::getStatus, "READY")
                .set(Documents::getCurrentVersion, version.getVersionNumber())
                .set(Documents::getPendingVersion, null)
                .set(Documents::getDocumentName, version.getDocumentName())
                .set(Documents::getDocumentType, version.getDocumentType())
                .set(Documents::getMimeType, version.getMimeType())
                .set(Documents::getSource, version.getSource())
                .set(Documents::getContentHash, version.getContentHash())
                .set(Documents::getFileSize, version.getFileSize())
                .set(Documents::getOriginalPath, version.getOriginalPath())
                .set(Documents::getChunkCount, version.getChunkCount())
                .set(Documents::getFeishuUpdateTime, version.getFeishuUpdateTime())
                .set(Documents::getRemoteMissingCount, 0)
                .set(Documents::getLastError, null)
                .set(Documents::getRowVersion, nextRowVersion));
        if (activated != 1) {
            throw new IllegalStateException("Document staging version is no longer current");
        }
        document.setStatus("READY");
        document.setCurrentVersion(version.getVersionNumber());
        document.setPendingVersion(null);
        document.setDocumentName(version.getDocumentName());
        document.setChunkCount(version.getChunkCount());
        document.setRowVersion(nextRowVersion);

        return new ActivationResult(
                new DocumentInfo(document.getId(), document.getDocumentName(), document.getChunkCount()),
                staleIds);
    }

    @Transactional
    public void fail(IndexStage stage, String message, boolean cancelled) {
        String status = cancelled ? "CANCELLED" : "FAILED";
        chunksMapper.update(null, Wrappers.<DocumentChunks>lambdaUpdate()
                .eq(DocumentChunks::getDocumentId, stage.document().getId())
                .eq(DocumentChunks::getDocumentVersion, stage.version().getVersionNumber())
                .set(DocumentChunks::getStatus, status));
        versionsMapper.update(null, Wrappers.<DocumentVersion>lambdaUpdate()
                .eq(DocumentVersion::getId, stage.version().getId())
                .set(DocumentVersion::getStatus, status)
                .set(DocumentVersion::getErrorMessage, abbreviate(message)));

        Documents document = documentsMapper.selectById(stage.document().getId());
        if (document != null && stage.version().getVersionNumber().equals(document.getPendingVersion())) {
            var failure = Wrappers.<Documents>lambdaUpdate()
                    .eq(Documents::getId, document.getId())
                    .eq(Documents::getPendingVersion, stage.version().getVersionNumber())
                    .set(Documents::getPendingVersion, null)
                    .set(Documents::getLastError, abbreviate(message));
            if (document.getCurrentVersion() == null || document.getCurrentVersion() == 0) {
                failure.set(Documents::getStatus, status);
            }
            documentsMapper.update(null, failure);
        }
    }

    @Transactional
    public List<String> beginDelete(long spaceId, long documentId) {
        Documents document = documentsMapper.selectOne(Wrappers.<Documents>lambdaQuery()
                .eq(Documents::getId, documentId)
                .eq(Documents::getSpaceId, spaceId));
        if (document == null || "DELETED".equals(document.getStatus())) {
            throw new IllegalArgumentException("Document does not exist in this knowledge space");
        }
        document.setStatus("DELETING");
        documentsMapper.updateById(document);
        return chunksMapper.selectList(Wrappers.<DocumentChunks>lambdaQuery()
                        .eq(DocumentChunks::getDocumentId, documentId)
                        .ne(DocumentChunks::getStatus, "DELETED"))
                .stream().map(DocumentChunks::getVectorId).toList();
    }

    @Transactional
    public void completeDelete(long spaceId, long documentId) {
        chunksMapper.update(null, Wrappers.<DocumentChunks>lambdaUpdate()
                .eq(DocumentChunks::getSpaceId, spaceId)
                .eq(DocumentChunks::getDocumentId, documentId)
                .set(DocumentChunks::getStatus, "DELETED"));
        versionsMapper.update(null, Wrappers.<DocumentVersion>lambdaUpdate()
                .eq(DocumentVersion::getSpaceId, spaceId)
                .eq(DocumentVersion::getDocumentId, documentId)
                .set(DocumentVersion::getStatus, "DELETED"));
        documentsMapper.update(null, Wrappers.<Documents>lambdaUpdate()
                .eq(Documents::getSpaceId, spaceId)
                .eq(Documents::getId, documentId)
                .set(Documents::getStatus, "DELETED")
                .set(Documents::getDeletedAt, LocalDateTime.now())
                .set(Documents::getPendingVersion, null));
    }

    @Transactional
    public void failDelete(long spaceId, long documentId, Collection<String> vectorIds, Throwable error) {
        documentsMapper.update(null, Wrappers.<Documents>lambdaUpdate()
                .eq(Documents::getSpaceId, spaceId)
                .eq(Documents::getId, documentId)
                .set(Documents::getStatus, "DELETE_FAILED")
                .set(Documents::getLastError, abbreviate(error.getMessage())));
        enqueueDeletes(spaceId, documentId, vectorIds, error);
    }

    @Transactional
    public void enqueueDeletes(long spaceId, Long documentId, Collection<String> vectorIds, Throwable error) {
        if (vectorIds == null) {
            return;
        }
        for (String vectorId : vectorIds) {
            VectorReconciliation item = new VectorReconciliation();
            item.setSpaceId(spaceId);
            item.setDocumentId(documentId);
            item.setVectorId(vectorId);
            item.setOperation("DELETE");
            item.setStatus("PENDING");
            item.setAttempts(0);
            item.setLastError(abbreviate(error == null ? null : error.getMessage()));
            item.setNextRetryTime(LocalDateTime.now());
            try {
                reconciliationMapper.insert(item);
            } catch (DuplicateKeyException ignored) {
                // The existing pending operation already guarantees eventual cleanup.
            }
        }
    }

    private Documents findExisting(DocumentDescriptor descriptor) {
        if ("FEISHU".equals(descriptor.source())) {
            return documentsMapper.selectOne(Wrappers.<Documents>lambdaQuery()
                    .eq(Documents::getSpaceId, descriptor.spaceId())
                    .eq(Documents::getRemoteSpaceId, descriptor.remoteSpaceId())
                    .eq(Documents::getFeishuNodeToken, descriptor.feishuNodeToken())
                    .ne(Documents::getStatus, "DELETED"));
        }
        if (descriptor.contentHash() != null) {
            Documents byContent = documentsMapper.selectOne(Wrappers.<Documents>lambdaQuery()
                    .eq(Documents::getSpaceId, descriptor.spaceId())
                    .eq(Documents::getContentHash, descriptor.contentHash())
                    .ne(Documents::getStatus, "DELETED")
                    .last("LIMIT 1"));
            if (byContent != null) {
                return byContent;
            }
        }
        return documentsMapper.selectOne(Wrappers.<Documents>lambdaQuery()
                .eq(Documents::getSpaceId, descriptor.spaceId())
                .eq(Documents::getDocumentName, descriptor.documentName())
                .in(Documents::getSource, "UPLOAD", "SCAN")
                .ne(Documents::getStatus, "DELETED")
                .orderByDesc(Documents::getId)
                .last("LIMIT 1"));
    }

    private boolean isDuplicate(DocumentDescriptor descriptor, Documents existing) {
        if (existing == null) {
            return false;
        }
        if ("FEISHU".equals(descriptor.source())) {
            return existing.getFeishuUpdateTime() != null
                    && existing.getFeishuUpdateTime() >= descriptor.feishuUpdateTime()
                    && "READY".equals(existing.getStatus());
        }
        return "READY".equals(existing.getStatus())
                && descriptor.contentHash() != null
                && descriptor.contentHash().equals(existing.getContentHash());
    }

    private int nextVersion(long documentId) {
        DocumentVersion latest = versionsMapper.selectOne(Wrappers.<DocumentVersion>lambdaQuery()
                .eq(DocumentVersion::getDocumentId, documentId)
                .orderByDesc(DocumentVersion::getVersionNumber)
                .last("LIMIT 1"));
        return latest == null ? 1 : latest.getVersionNumber() + 1;
    }

    private static String vectorId(long spaceId, long documentId, int version, int index, String chunkHash) {
        return com.rag.studyhelper.utils.Hashing.sha256(
                spaceId + ":" + documentId + ":" + version + ":" + index + ":" + chunkHash);
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
