package com.rag.studyhelper.feishu.service;

import com.rag.studyhelper.feishu.client.FeishuEnumeration;
import com.rag.studyhelper.feishu.client.FeishuRemoteGateway;
import com.rag.studyhelper.feishu.client.WikiNode;
import com.rag.studyhelper.feishu.config.FeishuProperties;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.mapper.FeishuSyncRunMapper;
import com.rag.studyhelper.model.Documents;
import com.rag.studyhelper.model.FeishuSyncRun;
import com.rag.studyhelper.service.DocumentIngestionService;
import com.rag.studyhelper.support.MybatisMetadata;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeishuSyncSafetyTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisMetadata.initialize(Documents.class, FeishuSyncRun.class);
    }

    @Mock
    private FeishuRemoteGateway remote;
    @Mock
    private DocumentIngestionService ingestion;
    @Mock
    private DocumentsMapper documents;
    @Mock
    private FeishuSyncRunMapper runs;
    @Mock
    private RedissonClient redisson;
    @Mock
    private RLock lock;

    private FeishuProperties properties;
    private FeishuSyncService service;

    @BeforeEach
    void setUp() throws Exception {
        properties = new FeishuProperties()
                .setSpaceId("remote-space")
                .setLocalSpaceId(1L)
                .setMissingConfirmations(2)
                .setMaxDeleteCount(10)
                .setMaxDeleteRatio(1.0)
                .setProtectZeroRemote(false)
                .setLockLeaseSeconds(30);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, 30, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        AtomicLong ids = new AtomicLong(100);
        doAnswer(invocation -> {
            FeishuSyncRun run = invocation.getArgument(0);
            run.setId(ids.incrementAndGet());
            return 1;
        }).when(runs).insert(any(FeishuSyncRun.class));
        service = new FeishuSyncService(remote, ingestion, properties, documents, runs, redisson);
    }

    @Test
    void enumerationFailureNeverDeletesAndBreaksMissingSequence() throws Exception {
        when(remote.enumerate("remote-space")).thenThrow(new IOException("page timeout"));

        FeishuSyncReport report = service.syncWiki();

        assertEquals("FAILED", report.status());
        assertFalse(report.enumerationComplete());
        verify(ingestion, never()).deleteDocument(anyLong(), anyLong());
        verify(documents).update(any(), any());
    }

    @Test
    void remoteDeleteRequiresTwoCompleteSuccessfulMissingRuns() throws Exception {
        Documents missing = remoteDocument(7L, "missing", 0);
        when(remote.enumerate("remote-space"))
                .thenReturn(new FeishuEnumeration(List.of(), true, 1, 0, 0));
        when(documents.selectList(any())).thenReturn(List.of(missing));

        FeishuSyncReport first = service.syncWiki();
        FeishuSyncReport second = service.syncWiki();

        assertEquals(0, first.deleted());
        assertEquals(1, second.deleted());
        verify(ingestion).deleteDocument(1L, 7L);
    }

    @Test
    void suspiciousDeleteRatioProtectsAllEligibleDocuments() throws Exception {
        properties.setMaxDeleteRatio(0.25);
        Documents missing = remoteDocument(8L, "missing", 1);
        when(remote.enumerate("remote-space"))
                .thenReturn(new FeishuEnumeration(List.of(), true, 1, 0, 0));
        when(documents.selectList(any())).thenReturn(List.of(missing));

        FeishuSyncReport report = service.syncWiki();

        assertEquals("SUCCEEDED", report.status());
        assertEquals(1, report.protectedDeletes());
        assertTrue(report.guardReason().contains("ratio"));
        verify(ingestion, never()).deleteDocument(anyLong(), anyLong());
    }

    @Test
    void contentFailureBlocksDeletePhaseForTheEntireRun() throws Exception {
        WikiNode node = new WikiNode("present", "doc", "docx", "Title", null, false, 10);
        when(remote.enumerate("remote-space"))
                .thenReturn(new FeishuEnumeration(List.of(node), true, 1, 0, 10));
        when(remote.readContent(node)).thenThrow(new IOException("content timeout"));
        when(documents.selectOne(any())).thenReturn(null);

        FeishuSyncReport report = service.syncWiki();

        assertEquals("PARTIAL", report.status());
        assertEquals(1, report.failed());
        assertTrue(report.guardReason().contains("blocked"));
        verify(ingestion, never()).deleteDocument(anyLong(), anyLong());
    }

    private Documents remoteDocument(long id, String token, int missingCount) {
        Documents document = new Documents();
        document.setId(id);
        document.setSpaceId(1L);
        document.setSource("FEISHU");
        document.setRemoteSpaceId("remote-space");
        document.setFeishuNodeToken(token);
        document.setStatus("READY");
        document.setRemoteMissingCount(missingCount);
        return document;
    }
}
