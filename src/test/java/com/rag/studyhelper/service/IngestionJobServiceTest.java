package com.rag.studyhelper.service;

import com.rag.studyhelper.ingestion.IngestionControl;
import com.rag.studyhelper.mapper.IngestionJobMapper;
import com.rag.studyhelper.model.DocumentInfo;
import com.rag.studyhelper.model.IngestionJob;
import com.rag.studyhelper.support.MybatisMetadata;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionJobServiceTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisMetadata.initialize(IngestionJob.class);
    }

    @Mock
    private IngestionJobMapper jobs;
    @Mock
    private DocumentIngestionService ingestion;
    @Mock
    private KnowledgeSpaceService spaces;
    @Mock
    private TaskExecutor executor;

    @TempDir
    Path temp;

    private Path inbox;
    private Path scan;
    private IngestionJobService service;

    @BeforeEach
    void setUp() throws Exception {
        inbox = Files.createDirectories(temp.resolve("inbox"));
        scan = Files.createDirectories(temp.resolve("scan"));
        service = new IngestionJobService(
                jobs, ingestion, spaces, executor, inbox.toString(), scan.toString());
        ReflectionTestUtils.setField(service, "maxDocumentBytes", 1024L);
        ReflectionTestUtils.setField(service, "staleProcessingSeconds", 600L);
        ReflectionTestUtils.setField(service, "recoveryBatchSize", 100);
    }

    @Test
    void onlyTheWorkerThatAtomicallyClaimsAQueuedJobCanProcessIt() {
        when(jobs.update(isNull(), any())).thenReturn(0);

        service.runJob(42L);

        verify(jobs, never()).selectById(42L);
        verifyNoInteractions(ingestion);
    }

    @Test
    void completedUploadDeletesOnlyItsInboxPayload() throws Exception {
        Path payload = Files.writeString(inbox.resolve("upload.payload"), "# notes");
        IngestionJob claimed = job(7L, "UPLOAD", "PROCESSING", payload);
        when(jobs.update(isNull(), any())).thenReturn(1);
        when(jobs.selectById(7L)).thenReturn(claimed);
        when(ingestion.ingestDocument(eq(1L), eq("notes.md"), isNull(),
                any(InputStream.class), any(IngestionControl.class)))
                .thenReturn(new DocumentInfo(99L, "notes.md", 1));

        service.runJob(7L);

        assertFalse(Files.exists(payload));
        verify(ingestion).ingestDocument(eq(1L), eq("notes.md"), isNull(),
                any(InputStream.class), any(IngestionControl.class));
    }

    @Test
    void forgedPayloadOutsideConfiguredRootIsNeverReadOrDeleted() throws Exception {
        Path outside = Files.writeString(temp.resolve("outside.md"), "private");
        IngestionJob claimed = job(8L, "UPLOAD", "PROCESSING", outside);
        when(jobs.update(isNull(), any())).thenReturn(1);
        when(jobs.selectById(8L)).thenReturn(claimed);

        service.runJob(8L);

        assertTrue(Files.exists(outside));
        verifyNoInteractions(ingestion);
    }

    @Test
    void uploadRejectsUnsupportedAndOversizedFilesBeforeCreatingAJob() {
        MockMultipartFile unsupported = new MockMultipartFile(
                "file", "payload.exe", "application/octet-stream", new byte[]{1});
        assertThrows(IllegalArgumentException.class,
                () -> service.submitUpload(1L, unsupported, null));

        ReflectionTestUtils.setField(service, "maxDocumentBytes", 3L);
        MockMultipartFile oversized = new MockMultipartFile(
                "file", "notes.md", "text/markdown", new byte[]{1, 2, 3, 4});
        assertThrows(IllegalArgumentException.class,
                () -> service.submitUpload(1L, oversized, null));

        verify(jobs, never()).insert(any(IngestionJob.class));
        verifyNoInteractions(executor);
    }

    @Test
    void duplicateContentReturnsExistingJobWithoutLeavingATempPayload() throws Exception {
        IngestionJob existing = job(9L, "UPLOAD", "COMPLETED", null);
        when(jobs.selectOne(any())).thenReturn(existing);
        MockMultipartFile upload = new MockMultipartFile(
                "file", "notes.md", "text/markdown", "same".getBytes());

        IngestionJob result = service.submitUpload(1L, upload, null);

        assertSame(existing, result);
        try (var files = Files.list(inbox)) {
            assertEquals(0, files.count());
        }
        verify(jobs, never()).insert(any(IngestionJob.class));
    }

    @Test
    void successfulUploadPersistsAQueuedJobAndDispatchesItOnce() throws Exception {
        when(jobs.selectOne(any())).thenReturn((IngestionJob) null);
        when(jobs.insert(any(IngestionJob.class))).thenAnswer(invocation -> {
            IngestionJob inserted = invocation.getArgument(0);
            inserted.setId(20L);
            return 1;
        });
        MockMultipartFile upload = new MockMultipartFile(
                "file", "folder/notes.md", "text/markdown", "study notes".getBytes());

        IngestionJob result = service.submitUpload(1L, upload, "upload:20");

        assertEquals(20L, result.getId());
        assertEquals("notes.md", result.getFileName());
        assertEquals("QUEUED", result.getStatus());
        assertTrue(Files.isRegularFile(Path.of(result.getPayloadPath())));
        verify(executor, times(1)).execute(any(Runnable.class));
    }

    @Test
    void concurrentUploadInsertReturnsWinnerAndRemovesLosingPayload() throws Exception {
        IngestionJob winner = job(21L, "UPLOAD", "QUEUED", null);
        when(jobs.selectOne(any())).thenReturn((IngestionJob) null, winner);
        when(jobs.insert(any(IngestionJob.class)))
                .thenThrow(new DuplicateKeyException("concurrent idempotency key"));
        MockMultipartFile upload = new MockMultipartFile(
                "file", "notes.md", "text/markdown", "same content".getBytes());

        IngestionJob result = service.submitUpload(1L, upload, "upload:race");

        assertSame(winner, result);
        try (var files = Files.list(inbox)) {
            assertEquals(0, files.count());
        }
        verifyNoInteractions(executor);
    }

    @Test
    void scanSkipsUnsupportedFilesReusesDuplicatesAndDispatchesNewJobs() throws Exception {
        Files.writeString(scan.resolve("a.md"), "existing");
        Files.writeString(scan.resolve("b.exe"), "ignored");
        Files.writeString(scan.resolve("c.txt"), "new");
        IngestionJob existing = job(22L, "SCAN", "COMPLETED", scan.resolve("a.md"));
        when(jobs.selectOne(any())).thenReturn(existing, (IngestionJob) null);
        when(jobs.insert(any(IngestionJob.class))).thenAnswer(invocation -> {
            IngestionJob inserted = invocation.getArgument(0);
            inserted.setId(23L);
            return 1;
        });

        List<IngestionJob> submitted = service.submitScan(1L);

        assertEquals(List.of(22L, 23L), submitted.stream().map(IngestionJob::getId).toList());
        assertEquals("c.txt", submitted.get(1).getFileName());
        verify(executor, times(1)).execute(any(Runnable.class));
    }

    @Test
    void scanRejectsAnOversizedSupportedDocument() throws Exception {
        ReflectionTestUtils.setField(service, "maxDocumentBytes", 3L);
        Files.write(scan.resolve("large.md"), new byte[]{1, 2, 3, 4});

        assertThrows(IllegalArgumentException.class, () -> service.submitScan(1L));

        verify(jobs, never()).insert(any(IngestionJob.class));
        verifyNoInteractions(executor);
    }

    @Test
    void deleteJobCreationAndListingUseTheKnowledgeSpaceBoundary() {
        when(jobs.selectOne(any())).thenReturn((IngestionJob) null);
        when(jobs.insert(any(IngestionJob.class))).thenAnswer(invocation -> {
            IngestionJob inserted = invocation.getArgument(0);
            inserted.setId(24L);
            return 1;
        });

        IngestionJob created = service.submitDelete(1L, 99L, null);

        assertEquals("DELETE", created.getOperation());
        assertEquals(99L, created.getDocumentId());
        verify(executor).execute(any(Runnable.class));

        when(jobs.selectList(any())).thenReturn(List.of(created));
        assertEquals(List.of(created), service.list(1L));
        verify(spaces, times(2)).requireActive(1L);
    }

    @Test
    void getRejectsAJobFromAnotherOrMissingKnowledgeSpace() {
        when(jobs.selectOne(any())).thenReturn((IngestionJob) null);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> service.get(2L, 25L));

        assertTrue(error.getMessage().contains("does not exist"));
    }

    @Test
    void processingCancellationUsesAConditionalUpdateAndReturnsFreshState() {
        IngestionJob processing = job(10L, "UPLOAD", "PROCESSING", null);
        IngestionJob cancelled = job(10L, "UPLOAD", "PROCESSING", null);
        cancelled.setCancelRequested(true);
        when(jobs.selectOne(any())).thenReturn(processing, cancelled);
        when(jobs.update(isNull(), any())).thenReturn(0, 1);

        IngestionJob result = service.cancel(1L, 10L);

        assertTrue(result.getCancelRequested());
        verify(jobs, never()).updateById(any(IngestionJob.class));
    }

    @Test
    void queuedCancellationMovesTheJobDirectlyToATerminalState() {
        IngestionJob queued = job(26L, "UPLOAD", "QUEUED", null);
        IngestionJob cancelled = job(26L, "UPLOAD", "CANCELLED", null);
        cancelled.setCancelRequested(true);
        when(jobs.selectOne(any())).thenReturn(queued, cancelled);
        when(jobs.update(isNull(), any())).thenReturn(1);

        IngestionJob result = service.cancel(1L, 26L);

        assertEquals("CANCELLED", result.getStatus());
        assertTrue(result.getCancelRequested());
        verify(jobs, times(1)).update(isNull(), any());
    }

    @Test
    void terminalCancellationAndInvalidRetriesAreRejected() {
        IngestionJob completed = job(27L, "DELETE", "COMPLETED", null);
        when(jobs.selectOne(any())).thenReturn(completed);
        assertThrows(IllegalStateException.class, () -> service.cancel(1L, 27L));
        assertThrows(IllegalStateException.class, () -> service.retry(1L, 27L));

        IngestionJob exhausted = job(28L, "DELETE", "FAILED", null);
        exhausted.setAttempts(3);
        exhausted.setMaxAttempts(3);
        when(jobs.selectOne(any())).thenReturn(exhausted);
        assertThrows(IllegalStateException.class, () -> service.retry(1L, 28L));

        IngestionJob missingPayload = job(29L, "SCAN", "FAILED", scan.resolve("missing.md"));
        when(jobs.selectOne(any())).thenReturn(missingPayload);
        assertThrows(IllegalStateException.class, () -> service.retry(1L, 29L));
    }

    @Test
    void failedDeleteCanBeRetriedWithoutAFilePayload() {
        IngestionJob failed = job(30L, "DELETE", "FAILED", null);
        IngestionJob queued = job(30L, "DELETE", "QUEUED", null);
        when(jobs.selectOne(any())).thenReturn(failed, queued);
        when(jobs.update(isNull(), any())).thenReturn(1);

        IngestionJob result = service.retry(1L, 30L);

        assertEquals("QUEUED", result.getStatus());
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void retryDetectsAConcurrentStateChange() {
        IngestionJob failed = job(31L, "DELETE", "FAILED", null);
        when(jobs.selectOne(any())).thenReturn(failed);
        when(jobs.update(isNull(), any())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.retry(1L, 31L));

        verifyNoInteractions(executor);
    }

    @Test
    void scanAndDeleteWorkersCompleteThroughTheSameAtomicClaimPath() throws Exception {
        Path scanned = Files.writeString(scan.resolve("worker.md"), "worker content");
        IngestionJob scanJob = job(32L, "SCAN", "PROCESSING", scanned);
        when(jobs.update(isNull(), any())).thenReturn(1);
        when(jobs.selectById(32L)).thenReturn(scanJob);
        when(ingestion.ingestScannedDocument(eq(1L), eq(scanned.toRealPath()),
                any(IngestionControl.class))).thenReturn(new DocumentInfo(102L, "worker.md", 1));

        service.runJob(32L);

        verify(ingestion).ingestScannedDocument(eq(1L), eq(scanned.toRealPath()),
                any(IngestionControl.class));

        IngestionJob deleteJob = job(33L, "DELETE", "PROCESSING", null);
        deleteJob.setDocumentId(102L);
        when(jobs.selectById(33L)).thenReturn(deleteJob, deleteJob);

        service.runJob(33L);

        verify(ingestion).deleteDocument(1L, 102L);
    }

    @Test
    void workerMarksUnsupportedOperationsAndNestedFailuresAsFailed() throws Exception {
        IngestionJob unsupported = job(34L, "UNKNOWN", "PROCESSING", null);
        when(jobs.update(isNull(), any())).thenReturn(1);
        when(jobs.selectById(34L)).thenReturn(unsupported);
        service.runJob(34L);

        Path payload = Files.writeString(inbox.resolve("failure.payload"), "bad");
        IngestionJob upload = job(35L, "UPLOAD", "PROCESSING", payload);
        when(jobs.selectById(35L)).thenReturn(upload);
        when(ingestion.ingestDocument(eq(1L), eq("notes.md"), isNull(),
                any(InputStream.class), any(IngestionControl.class)))
                .thenThrow(new IOException("outer", new IllegalStateException("root failure")));

        service.runJob(35L);

        assertTrue(Files.exists(payload));
        verify(jobs, atLeast(4)).update(isNull(), any());
    }

    @Test
    void ingestionControlPersistsProgressAndObservesCancellation() throws Exception {
        Path payload = Files.writeString(inbox.resolve("cancel.payload"), "cancel");
        IngestionJob upload = job(36L, "UPLOAD", "PROCESSING", payload);
        IngestionJob cancelRequested = job(36L, "UPLOAD", "PROCESSING", payload);
        cancelRequested.setCancelRequested(true);
        when(jobs.update(isNull(), any())).thenReturn(1);
        when(jobs.selectById(36L)).thenReturn(upload, cancelRequested);
        when(ingestion.ingestDocument(eq(1L), eq("notes.md"), isNull(),
                any(InputStream.class), any(IngestionControl.class))).thenAnswer(invocation -> {
            IngestionControl control = invocation.getArgument(4);
            control.progress("embedding", 2, 4);
            assertTrue(control.isCancellationRequested());
            throw new com.rag.studyhelper.ingestion.IngestionCancelledException();
        });

        service.runJob(36L);

        assertTrue(Files.exists(payload));
        verify(jobs, atLeast(3)).update(isNull(), any());
    }

    @Test
    void malformedUploadMetadataAndIdempotencyKeysAreRejected() {
        MockMultipartFile empty = new MockMultipartFile(
                "file", "empty.md", "text/markdown", new byte[0]);
        assertThrows(IllegalArgumentException.class, () -> service.submitUpload(1L, empty, null));

        MockMultipartFile missingName = new MockMultipartFile(
                "file", null, "text/markdown", "x".getBytes());
        assertThrows(IllegalArgumentException.class,
                () -> service.submitUpload(1L, missingName, null));

        MockMultipartFile valid = new MockMultipartFile(
                "file", "notes.md", "text/markdown", "x".getBytes());
        assertThrows(IllegalArgumentException.class,
                () -> service.submitUpload(1L, valid, "contains spaces"));

        verify(jobs, never()).insert(any(IngestionJob.class));
    }

    @Test
    void recoveryDispatchesQueuedJobsAfterRepairingStaleStates() {
        IngestionJob queued = job(11L, "SCAN", "QUEUED", scan.resolve("a.md"));
        when(jobs.update(isNull(), any())).thenReturn(0);
        when(jobs.selectList(any())).thenReturn(List.of(queued));

        service.recoverInterruptedJobs();

        verify(executor).execute(any(Runnable.class));
    }

    private IngestionJob job(long id, String operation, String status, Path payload) {
        IngestionJob job = new IngestionJob();
        job.setId(id);
        job.setSpaceId(1L);
        job.setOperation(operation);
        job.setStatus(status);
        job.setIdempotencyKey(operation + ":" + id);
        job.setFileName("notes.md");
        job.setPayloadPath(payload == null ? null : payload.toString());
        job.setProgressCurrent(0);
        job.setProgressTotal(4);
        job.setAttempts(1);
        job.setMaxAttempts(3);
        job.setCancelRequested(false);
        return job;
    }
}
