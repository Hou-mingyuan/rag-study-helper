package com.rag.studyhelper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rag.studyhelper.ingestion.IngestionCancelledException;
import com.rag.studyhelper.ingestion.IngestionControl;
import com.rag.studyhelper.mapper.IngestionJobMapper;
import com.rag.studyhelper.model.DocumentInfo;
import com.rag.studyhelper.model.IngestionJob;
import com.rag.studyhelper.utils.Hashing;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class IngestionJobService {

    private static final int MAX_LIST_SIZE = 100;

    private final IngestionJobMapper jobsMapper;
    private final DocumentIngestionService ingestionService;
    private final KnowledgeSpaceService spaceService;
    private final TaskExecutor executor;
    private final Path inboxRoot;
    private final Path scanRoot;

    @Value("${app.rag.max-document-bytes:52428800}")
    private long maxDocumentBytes = 52_428_800L;

    @Value("${app.rag.ingestion-stale-seconds:600}")
    private long staleProcessingSeconds = 600L;

    @Value("${app.rag.ingestion-recovery-batch-size:100}")
    private int recoveryBatchSize = 100;

    public IngestionJobService(
            IngestionJobMapper jobsMapper,
            DocumentIngestionService ingestionService,
            KnowledgeSpaceService spaceService,
            @Qualifier("ingestionTaskExecutor") TaskExecutor executor,
            @Value("${app.rag.inbox-path:data/inbox}") String inboxPath,
            @Value("${app.rag.document-scan-path:data/docs}") String scanPath) {
        this.jobsMapper = jobsMapper;
        this.ingestionService = ingestionService;
        this.spaceService = spaceService;
        this.executor = executor;
        this.inboxRoot = Path.of(inboxPath).toAbsolutePath().normalize();
        this.scanRoot = Path.of(scanPath).toAbsolutePath().normalize();
    }

    public IngestionJob submitUpload(long spaceId, MultipartFile file, String requestedKey) throws IOException {
        spaceService.requireActive(spaceId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Upload file is empty");
        }
        String fileName = safeFileName(file.getOriginalFilename());
        if (!isSupported(fileName)) {
            throw new IllegalArgumentException("Unsupported document type: " + extension(fileName));
        }
        if (file.getSize() > maxDocumentBytes) {
            throw new IllegalArgumentException("Upload exceeds the configured document size limit");
        }
        byte[] content = file.getBytes();
        if (content.length > maxDocumentBytes) {
            throw new IllegalArgumentException("Upload exceeds the configured document size limit");
        }
        String contentHash = Hashing.sha256(content);
        String key = normalizeIdempotencyKey(requestedKey, "UPLOAD:" + contentHash);

        IngestionJob existing = findByKey(spaceId, key);
        if (existing != null) {
            return existing;
        }

        Files.createDirectories(inboxRoot);
        Path payload = Files.createTempFile(inboxRoot, "upload-", ".payload").normalize();
        Files.write(payload, content, StandardOpenOption.TRUNCATE_EXISTING);
        JobCreation creation;
        try {
            creation = createJob(spaceId, null, "UPLOAD", key, fileName, payload.toString());
        } catch (RuntimeException error) {
            Files.deleteIfExists(payload);
            throw error;
        }
        if (!creation.created()) {
            Files.deleteIfExists(payload);
            return creation.job();
        }
        dispatch(creation.job().getId());
        return creation.job();
    }

    public List<IngestionJob> submitScan(long spaceId) throws IOException {
        spaceService.requireActive(spaceId);
        Files.createDirectories(scanRoot);
        List<IngestionJob> submitted = new ArrayList<>();
        try (var paths = Files.walk(scanRoot)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(candidate -> !Files.isSymbolicLink(candidate)).sorted().toList()) {
                if (!isSupported(path.getFileName().toString())) {
                    continue;
                }
                if (Files.size(path) > maxDocumentBytes) {
                    throw new IllegalArgumentException(
                            "Scanned file exceeds the configured document size limit: "
                                    + path.getFileName());
                }
                String hash = Hashing.sha256(path);
                String key = "SCAN:" + Hashing.sha256(path.toString()) + ":" + hash;
                IngestionJob existing = findByKey(spaceId, key);
                if (existing != null) {
                    submitted.add(existing);
                    continue;
                }
                JobCreation creation = createJob(spaceId, null, "SCAN", key,
                        path.getFileName().toString(), path.toString());
                submitted.add(creation.job());
                if (creation.created()) {
                    dispatch(creation.job().getId());
                }
            }
        }
        return submitted;
    }

    public IngestionJob submitDelete(long spaceId, long documentId, String requestedKey) {
        spaceService.requireActive(spaceId);
        String key = normalizeIdempotencyKey(requestedKey, "DELETE:" + documentId);
        IngestionJob existing = findByKey(spaceId, key);
        if (existing != null) {
            return existing;
        }
        JobCreation creation = createJob(spaceId, documentId, "DELETE", key, null, null);
        if (creation.created()) {
            dispatch(creation.job().getId());
        }
        return creation.job();
    }

    public List<IngestionJob> list(long spaceId) {
        spaceService.requireActive(spaceId);
        return jobsMapper.selectList(Wrappers.<IngestionJob>lambdaQuery()
                .eq(IngestionJob::getSpaceId, spaceId)
                .orderByDesc(IngestionJob::getCreateTime)
                .last("LIMIT " + MAX_LIST_SIZE));
    }

    public IngestionJob get(long spaceId, long jobId) {
        IngestionJob job = jobsMapper.selectOne(Wrappers.<IngestionJob>lambdaQuery()
                .eq(IngestionJob::getId, jobId)
                .eq(IngestionJob::getSpaceId, spaceId));
        if (job == null) {
            throw new IllegalArgumentException("Ingestion job does not exist in this knowledge space");
        }
        return job;
    }

    public IngestionJob cancel(long spaceId, long jobId) {
        IngestionJob job = get(spaceId, jobId);
        if (isTerminal(job.getStatus())) {
            throw new IllegalStateException("A completed job cannot be cancelled");
        }
        LocalDateTime cancelledAt = LocalDateTime.now();
        int updated = jobsMapper.update(null, Wrappers.<IngestionJob>lambdaUpdate()
                .eq(IngestionJob::getId, jobId)
                .eq(IngestionJob::getSpaceId, spaceId)
                .eq(IngestionJob::getStatus, "QUEUED")
                .set(IngestionJob::getCancelRequested, true)
                .set(IngestionJob::getStatus, "CANCELLED")
                .set(IngestionJob::getFinishTime, cancelledAt)
                .set(IngestionJob::getUpdateTime, cancelledAt));
        if (updated == 0) {
            updated = jobsMapper.update(null, Wrappers.<IngestionJob>lambdaUpdate()
                    .eq(IngestionJob::getId, jobId)
                    .eq(IngestionJob::getSpaceId, spaceId)
                    .eq(IngestionJob::getStatus, "PROCESSING")
                    .set(IngestionJob::getCancelRequested, true)
                    .set(IngestionJob::getUpdateTime, cancelledAt));
        }
        if (updated == 0) {
            IngestionJob changed = get(spaceId, jobId);
            if (isTerminal(changed.getStatus())) {
                throw new IllegalStateException("The job completed before it could be cancelled");
            }
            throw new IllegalStateException("The ingestion job changed before it could be cancelled");
        }
        return get(spaceId, jobId);
    }

    public IngestionJob retry(long spaceId, long jobId) {
        IngestionJob job = get(spaceId, jobId);
        if (!("FAILED".equals(job.getStatus()) || "CANCELLED".equals(job.getStatus()))) {
            throw new IllegalStateException("Only failed or cancelled jobs can be retried");
        }
        if (job.getAttempts() != null && job.getAttempts() >= job.getMaxAttempts()) {
            throw new IllegalStateException("The job retry limit has been reached");
        }
        if (!"DELETE".equals(job.getOperation())) {
            Path payload = requiredPayload(job);
            if (!Files.isRegularFile(payload)) {
                throw new IllegalStateException("The job payload no longer exists");
            }
        }
        LocalDateTime queuedAt = LocalDateTime.now();
        int updated = jobsMapper.update(null, Wrappers.<IngestionJob>lambdaUpdate()
                .eq(IngestionJob::getId, jobId)
                .eq(IngestionJob::getSpaceId, spaceId)
                .eq(IngestionJob::getStatus, job.getStatus())
                .set(IngestionJob::getStatus, "QUEUED")
                .set(IngestionJob::getCancelRequested, false)
                .set(IngestionJob::getErrorCode, null)
                .set(IngestionJob::getErrorMessage, null)
                .set(IngestionJob::getProgressCurrent, 0)
                .set(IngestionJob::getProgressTotal, 0)
                .set(IngestionJob::getStartTime, null)
                .set(IngestionJob::getFinishTime, null)
                .set(IngestionJob::getUpdateTime, queuedAt));
        if (updated != 1) {
            throw new IllegalStateException("The ingestion job changed before it could be retried");
        }
        dispatch(job.getId());
        return get(spaceId, jobId);
    }

    public void runJob(long jobId) {
        IngestionJob job = claim(jobId);
        if (job == null) {
            return;
        }

        try {
            DocumentInfo document = switch (job.getOperation()) {
                case "UPLOAD" -> runUpload(job);
                case "SCAN" -> ingestionService.ingestScannedDocument(
                        job.getSpaceId(), requiredPayload(job), control(job.getId()));
                case "DELETE" -> {
                    checkCancelled(job.getId());
                    ingestionService.deleteDocument(job.getSpaceId(), job.getDocumentId());
                    yield null;
                }
                default -> throw new IllegalStateException("Unsupported job operation: " + job.getOperation());
            };
            if (markCompleted(job.getId(), document == null ? job.getDocumentId() : document.getId())) {
                deleteUploadPayload(job);
            }
        } catch (IngestionCancelledException error) {
            markCancelled(job.getId());
        } catch (Exception error) {
            markFailed(job.getId(), error.getClass().getSimpleName(), rootMessage(error));
        }
    }

    @Scheduled(
            fixedDelayString = "${app.rag.ingestion-recovery-interval-ms:30000}",
            initialDelayString = "${app.rag.ingestion-recovery-initial-delay-ms:5000}")
    public void recoverInterruptedJobs() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minusSeconds(Math.max(60, staleProcessingSeconds));

        jobsMapper.update(null, Wrappers.<IngestionJob>lambdaUpdate()
                .eq(IngestionJob::getStatus, "PROCESSING")
                .eq(IngestionJob::getCancelRequested, true)
                .le(IngestionJob::getUpdateTime, staleBefore)
                .set(IngestionJob::getStatus, "CANCELLED")
                .set(IngestionJob::getFinishTime, now)
                .set(IngestionJob::getUpdateTime, now));
        jobsMapper.update(null, Wrappers.<IngestionJob>lambdaUpdate()
                .eq(IngestionJob::getStatus, "PROCESSING")
                .eq(IngestionJob::getCancelRequested, false)
                .apply("attempts >= max_attempts")
                .le(IngestionJob::getUpdateTime, staleBefore)
                .set(IngestionJob::getStatus, "FAILED")
                .set(IngestionJob::getErrorCode, "WORKER_INTERRUPTED")
                .set(IngestionJob::getErrorMessage, "The worker stopped before the job completed")
                .set(IngestionJob::getFinishTime, now)
                .set(IngestionJob::getUpdateTime, now));
        jobsMapper.update(null, Wrappers.<IngestionJob>lambdaUpdate()
                .eq(IngestionJob::getStatus, "PROCESSING")
                .eq(IngestionJob::getCancelRequested, false)
                .apply("attempts < max_attempts")
                .le(IngestionJob::getUpdateTime, staleBefore)
                .set(IngestionJob::getStatus, "QUEUED")
                .set(IngestionJob::getStartTime, null)
                .set(IngestionJob::getUpdateTime, now));

        int batch = Math.max(1, Math.min(recoveryBatchSize, 500));
        jobsMapper.selectList(Wrappers.<IngestionJob>lambdaQuery()
                        .eq(IngestionJob::getStatus, "QUEUED")
                        .eq(IngestionJob::getCancelRequested, false)
                        .orderByAsc(IngestionJob::getCreateTime)
                        .last("LIMIT " + batch))
                .forEach(job -> dispatch(job.getId()));
    }

    private IngestionJob claim(long jobId) {
        LocalDateTime startedAt = LocalDateTime.now();
        int updated = jobsMapper.update(null, Wrappers.<IngestionJob>lambdaUpdate()
                .eq(IngestionJob::getId, jobId)
                .eq(IngestionJob::getStatus, "QUEUED")
                .eq(IngestionJob::getCancelRequested, false)
                .apply("attempts < max_attempts")
                .set(IngestionJob::getStatus, "PROCESSING")
                .setSql("attempts = attempts + 1")
                .set(IngestionJob::getStartTime, startedAt)
                .set(IngestionJob::getProgressCurrent, 0)
                .set(IngestionJob::getProgressTotal, 4)
                .set(IngestionJob::getUpdateTime, startedAt));
        return updated == 1 ? jobsMapper.selectById(jobId) : null;
    }

    private DocumentInfo runUpload(IngestionJob job) throws IOException {
        Path payload = requiredPayload(job);
        try (InputStream input = Files.newInputStream(payload)) {
            return ingestionService.ingestDocument(job.getSpaceId(), job.getFileName(), null,
                    input, control(job.getId()));
        }
    }

    private IngestionControl control(long jobId) {
        return new IngestionControl() {
            @Override
            public void progress(String phase, int current, int total) {
                LocalDateTime updatedAt = LocalDateTime.now();
                jobsMapper.update(null, Wrappers.<IngestionJob>lambdaUpdate()
                        .eq(IngestionJob::getId, jobId)
                        .eq(IngestionJob::getStatus, "PROCESSING")
                        .set(IngestionJob::getProgressCurrent, current)
                        .set(IngestionJob::getProgressTotal, total)
                        .set(IngestionJob::getUpdateTime, updatedAt));
            }

            @Override
            public boolean isCancellationRequested() {
                IngestionJob current = jobsMapper.selectById(jobId);
                return current == null || Boolean.TRUE.equals(current.getCancelRequested());
            }
        };
    }

    private void checkCancelled(long jobId) {
        IngestionJob current = jobsMapper.selectById(jobId);
        if (current == null || Boolean.TRUE.equals(current.getCancelRequested())) {
            throw new IngestionCancelledException();
        }
    }

    private JobCreation createJob(long spaceId, Long documentId, String operation,
                                  String key, String fileName, String payloadPath) {
        IngestionJob job = new IngestionJob();
        job.setSpaceId(spaceId);
        job.setDocumentId(documentId);
        job.setOperation(operation);
        job.setStatus("QUEUED");
        job.setIdempotencyKey(key);
        job.setFileName(fileName);
        job.setPayloadPath(payloadPath);
        job.setProgressCurrent(0);
        job.setProgressTotal(0);
        job.setAttempts(0);
        job.setMaxAttempts(3);
        job.setCancelRequested(false);
        try {
            jobsMapper.insert(job);
            return new JobCreation(job, true);
        } catch (DuplicateKeyException race) {
            IngestionJob existing = findByKey(spaceId, key);
            if (existing != null) {
                return new JobCreation(existing, false);
            }
            throw race;
        }
    }

    private IngestionJob findByKey(long spaceId, String key) {
        return jobsMapper.selectOne(Wrappers.<IngestionJob>lambdaQuery()
                .eq(IngestionJob::getSpaceId, spaceId)
                .eq(IngestionJob::getIdempotencyKey, key));
    }

    private void dispatch(long jobId) {
        executor.execute(() -> runJob(jobId));
    }

    private boolean markCompleted(long jobId, Long documentId) {
        LocalDateTime completedAt = LocalDateTime.now();
        return jobsMapper.update(null, Wrappers.<IngestionJob>lambdaUpdate()
                .eq(IngestionJob::getId, jobId)
                .eq(IngestionJob::getStatus, "PROCESSING")
                .set(IngestionJob::getStatus, "COMPLETED")
                .set(IngestionJob::getDocumentId, documentId)
                .set(IngestionJob::getCancelRequested, false)
                .setSql("progress_current = progress_total")
                .set(IngestionJob::getFinishTime, completedAt)
                .set(IngestionJob::getUpdateTime, completedAt)) == 1;
    }

    private void markCancelled(long jobId) {
        LocalDateTime cancelledAt = LocalDateTime.now();
        jobsMapper.update(null, Wrappers.<IngestionJob>lambdaUpdate()
                .eq(IngestionJob::getId, jobId)
                .eq(IngestionJob::getStatus, "PROCESSING")
                .set(IngestionJob::getStatus, "CANCELLED")
                .set(IngestionJob::getFinishTime, cancelledAt)
                .set(IngestionJob::getUpdateTime, cancelledAt));
    }

    private void markFailed(long jobId, String code, String message) {
        LocalDateTime failedAt = LocalDateTime.now();
        jobsMapper.update(null, Wrappers.<IngestionJob>lambdaUpdate()
                .eq(IngestionJob::getId, jobId)
                .eq(IngestionJob::getStatus, "PROCESSING")
                .set(IngestionJob::getStatus, "FAILED")
                .set(IngestionJob::getErrorCode, abbreviate(code, 80))
                .set(IngestionJob::getErrorMessage, abbreviate(message, 1000))
                .set(IngestionJob::getFinishTime, failedAt)
                .set(IngestionJob::getUpdateTime, failedAt));
    }

    private Path requiredPayload(IngestionJob job) {
        if (job.getPayloadPath() == null || job.getPayloadPath().isBlank()) {
            throw new IllegalStateException("The job payload path is missing");
        }
        try {
            Path payload = Path.of(job.getPayloadPath()).toAbsolutePath().normalize();
            Path allowedRoot = switch (job.getOperation()) {
                case "UPLOAD" -> inboxRoot;
                case "SCAN" -> scanRoot;
                default -> throw new IllegalStateException("This job does not accept a payload");
            };
            Path realPayload = payload.toRealPath();
            Path realRoot = allowedRoot.toRealPath();
            if (!realPayload.startsWith(realRoot)) {
                throw new IllegalStateException("The job payload is outside its configured root");
            }
            return realPayload;
        } catch (InvalidPathException error) {
            throw new IllegalStateException("The job payload path is invalid", error);
        } catch (IOException error) {
            throw new IllegalStateException("The job payload is unavailable", error);
        }
    }

    private void deleteUploadPayload(IngestionJob job) {
        if (!"UPLOAD".equals(job.getOperation()) || job.getPayloadPath() == null) {
            return;
        }
        try {
            Files.deleteIfExists(requiredPayload(job));
            LocalDateTime cleanedAt = LocalDateTime.now();
            jobsMapper.update(null, Wrappers.<IngestionJob>lambdaUpdate()
                    .eq(IngestionJob::getId, job.getId())
                    .set(IngestionJob::getPayloadPath, null)
                    .set(IngestionJob::getUpdateTime, cleanedAt));
        } catch (IOException ignored) {
            // The completed job remains valid; stale inbox files are handled by maintenance.
        }
    }

    private static String safeFileName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("Upload file name is required");
        }
        try {
            String value = Path.of(originalName).getFileName().toString().trim();
            if (value.isEmpty() || value.length() > 255 || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid upload file name");
            }
            return value;
        } catch (InvalidPathException error) {
            throw new IllegalArgumentException("Invalid upload file name", error);
        }
    }

    private static String normalizeIdempotencyKey(String requested, String fallback) {
        String value = requested == null || requested.isBlank() ? fallback : requested.trim();
        if (value.length() > 160 || !value.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("Invalid Idempotency-Key");
        }
        return value;
    }

    private static boolean isSupported(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return List.of(".txt", ".md", ".csv", ".json", ".xml", ".pdf", ".xlsx", ".xls",
                ".docx", ".pptx", ".html", ".htm").stream().anyMatch(lower::endsWith);
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String abbreviate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private record JobCreation(IngestionJob job, boolean created) {
    }
}
