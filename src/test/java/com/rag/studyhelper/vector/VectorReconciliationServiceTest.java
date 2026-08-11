package com.rag.studyhelper.vector;

import com.rag.studyhelper.mapper.VectorReconciliationMapper;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.ingestion.IngestionPersistence;
import com.rag.studyhelper.model.Documents;
import com.rag.studyhelper.model.VectorReconciliation;
import com.rag.studyhelper.support.MybatisMetadata;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorReconciliationServiceTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisMetadata.initialize(VectorReconciliation.class, Documents.class);
    }

    @Mock
    private VectorReconciliationMapper mapper;
    @Mock
    private VectorStoreGateway vectors;
    @Mock
    private RedissonClient redisson;
    @Mock
    private DocumentsMapper documents;
    @Mock
    private IngestionPersistence persistence;
    @Mock
    private VectorIndexManager vectorIndex;
    @Mock
    private RLock lock;

    private VectorReconciliationService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new VectorReconciliationService(
                mapper, vectors, redisson, documents, persistence, vectorIndex,
                true, 20, 2, 1, 30);
        when(redisson.getLock("rag:vector-reconciliation")).thenReturn(lock);
        when(lock.tryLock(0, 30, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    void successfulDeletesCompleteWhileRepeatedFailureMovesToDeadLetter() {
        VectorReconciliation good = item(1L, "good-vector", 0);
        VectorReconciliation bad = item(2L, "bad-vector", 1);
        when(mapper.selectList(any())).thenReturn(List.of(good, bad));
        doAnswer(invocation -> {
            Collection<String> ids = invocation.getArgument(0);
            if (ids.contains("bad-vector")) {
                throw new IllegalStateException("vector store unavailable");
            }
            return null;
        }).when(vectors).delete(any());

        ReconciliationReport report = service.reconcile();

        assertEquals(new ReconciliationReport(true, 2, 1, 0, 1, 0), report);
        verify(vectors).delete(List.of("good-vector"));
        verify(vectors).delete(List.of("bad-vector"));
        verify(lock).unlock();
        verify(mapper, org.mockito.Mockito.times(2)).update(eq(null), any());
    }

    @Test
    void lastSuccessfulCleanupFinalizesDeleteFailedDocument() {
        VectorReconciliation item = item(3L, "last-vector", 0);
        item.setSpaceId(3L);
        item.setDocumentId(7L);
        Documents document = new Documents();
        document.setId(7L);
        document.setSpaceId(3L);
        document.setStatus("DELETE_FAILED");
        when(mapper.selectList(any())).thenReturn(List.of(item));
        when(documents.selectById(7L)).thenReturn(document);
        when(mapper.selectCount(any())).thenReturn(0L);

        ReconciliationReport report = service.reconcile();

        assertEquals(1, report.finalizedDeletes());
        verify(persistence).completeDelete(3L, 7L);
        verify(vectorIndex).refreshEntryCount();
    }

    private VectorReconciliation item(long id, String vectorId, int attempts) {
        VectorReconciliation item = new VectorReconciliation();
        item.setId(id);
        item.setVectorId(vectorId);
        item.setOperation("DELETE");
        item.setStatus("PENDING");
        item.setAttempts(attempts);
        return item;
    }
}
