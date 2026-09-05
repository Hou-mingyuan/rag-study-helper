package com.rag.studyhelper.vector;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rag.studyhelper.mapper.VectorReconciliationMapper;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.model.Documents;
import com.rag.studyhelper.model.VectorReconciliation;
import com.rag.studyhelper.ingestion.IngestionPersistence;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class VectorReconciliationService {

    private static final String LOCK_NAME = "rag:vector-reconciliation";

    private final VectorReconciliationMapper mapper;
    private final VectorStoreGateway vectors;
    private final RedissonClient redisson;
    private final DocumentsMapper documents;
    private final IngestionPersistence persistence;
    private final VectorIndexManager vectorIndex;
    private final boolean enabled;
    private final int batchSize;
    private final int maxAttempts;
    private final long baseBackoffSeconds;
    private final long lockLeaseSeconds;

    public VectorReconciliationService(
            VectorReconciliationMapper mapper,
            VectorStoreGateway vectors,
            RedissonClient redisson,
            DocumentsMapper documents,
            IngestionPersistence persistence,
            VectorIndexManager vectorIndex,
            @Value("${app.vector.reconciliation.enabled:true}") boolean enabled,
            @Value("${app.vector.reconciliation.batch-size:50}") int batchSize,
            @Value("${app.vector.reconciliation.max-attempts:8}") int maxAttempts,
            @Value("${app.vector.reconciliation.base-backoff-seconds:5}") long baseBackoffSeconds,
            @Value("${app.vector.reconciliation.lock-lease-seconds:60}") long lockLeaseSeconds) {
        this.mapper = mapper;
        this.vectors = vectors;
        this.redisson = redisson;
        this.documents = documents;
        this.persistence = persistence;
        this.vectorIndex = vectorIndex;
        this.enabled = enabled;
        this.batchSize = Math.min(Math.max(batchSize, 1), 500);
        this.maxAttempts = Math.min(Math.max(maxAttempts, 1), 100);
        this.baseBackoffSeconds = Math.max(baseBackoffSeconds, 1);
        this.lockLeaseSeconds = Math.max(lockLeaseSeconds, 5);
    }

    @Scheduled(
            fixedDelayString = "${app.vector.reconciliation.interval-millis:30000}",
            initialDelayString = "${app.vector.reconciliation.initial-delay-millis:15000}")
    public void scheduled() {
        if (enabled) {
            reconcile();
        }
    }

    public ReconciliationReport reconcile() {
        RLock lock = redisson.getLock(LOCK_NAME);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, lockLeaseSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                return new ReconciliationReport(false, 0, 0, 0, 0, 0);
            }
            return reconcileLocked();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ReconciliationReport(false, 0, 0, 0, 0, 0);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private ReconciliationReport reconcileLocked() {
        LocalDateTime now = LocalDateTime.now();
        List<VectorReconciliation> due = mapper.selectList(
                Wrappers.<VectorReconciliation>lambdaQuery()
                        .eq(VectorReconciliation::getStatus, "PENDING")
                        .and(query -> query.isNull(VectorReconciliation::getNextRetryTime)
                                .or().le(VectorReconciliation::getNextRetryTime, now))
                        .orderByAsc(VectorReconciliation::getId)
                        .last("LIMIT " + batchSize));
        int completed = 0;
        int retried = 0;
        int dead = 0;
        int finalizedDeletes = 0;
        for (VectorReconciliation item : due) {
            int attempts = (item.getAttempts() == null ? 0 : item.getAttempts()) + 1;
            try {
                if (!"DELETE".equals(item.getOperation())) {
                    throw new IllegalStateException("Unsupported reconciliation operation: "
                            + item.getOperation());
                }
                vectors.delete(List.of(item.getVectorId()));
                if (finalizeDocumentDeleteIfReady(item)) {
                    finalizedDeletes++;
                }
                update(item.getId(), "COMPLETED", attempts, null, null);
                completed++;
            } catch (RuntimeException error) {
                if (attempts >= maxAttempts) {
                    update(item.getId(), "DEAD", attempts, abbreviate(error.getMessage()), null);
                    dead++;
                } else {
                    long multiplier = 1L << Math.min(attempts - 1, 10);
                    LocalDateTime next = now.plusSeconds(Math.min(baseBackoffSeconds * multiplier, 3600));
                    update(item.getId(), "PENDING", attempts, abbreviate(error.getMessage()), next);
                    retried++;
                }
            }
        }
        return new ReconciliationReport(
                true, due.size(), completed, retried, dead, finalizedDeletes);
    }

    private boolean finalizeDocumentDeleteIfReady(VectorReconciliation item) {
        if (item.getDocumentId() == null || item.getSpaceId() == null) {
            return false;
        }
        Documents document = documents.selectById(item.getDocumentId());
        if (document == null || !"DELETE_FAILED".equals(document.getStatus())
                || !item.getSpaceId().equals(document.getSpaceId())) {
            return false;
        }
        long otherPending = mapper.selectCount(Wrappers.<VectorReconciliation>lambdaQuery()
                .eq(VectorReconciliation::getDocumentId, item.getDocumentId())
                .eq(VectorReconciliation::getOperation, "DELETE")
                .eq(VectorReconciliation::getStatus, "PENDING")
                .ne(VectorReconciliation::getId, item.getId()));
        long dead = mapper.selectCount(Wrappers.<VectorReconciliation>lambdaQuery()
                .eq(VectorReconciliation::getDocumentId, item.getDocumentId())
                .eq(VectorReconciliation::getOperation, "DELETE")
                .eq(VectorReconciliation::getStatus, "DEAD"));
        if (otherPending > 0 || dead > 0) {
            return false;
        }
        persistence.completeDelete(item.getSpaceId(), item.getDocumentId());
        vectorIndex.refreshEntryCount();
        return true;
    }

    private void update(Long id, String status, int attempts, String error, LocalDateTime nextRetry) {
        mapper.update(null, Wrappers.<VectorReconciliation>lambdaUpdate()
                .eq(VectorReconciliation::getId, id)
                .set(VectorReconciliation::getStatus, status)
                .set(VectorReconciliation::getAttempts, attempts)
                .set(VectorReconciliation::getLastError, error)
                .set(VectorReconciliation::getNextRetryTime, nextRetry)
                .set(VectorReconciliation::getUpdateTime, LocalDateTime.now()));
    }

    private static String abbreviate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }
}
