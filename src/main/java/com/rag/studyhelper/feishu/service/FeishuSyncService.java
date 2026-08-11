package com.rag.studyhelper.feishu.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rag.studyhelper.feishu.client.FeishuEnumeration;
import com.rag.studyhelper.feishu.client.FeishuRemoteGateway;
import com.rag.studyhelper.feishu.client.FeishuWikiSupport;
import com.rag.studyhelper.feishu.client.WikiNode;
import com.rag.studyhelper.feishu.config.FeishuProperties;
import com.rag.studyhelper.mapper.DocumentsMapper;
import com.rag.studyhelper.mapper.FeishuSyncRunMapper;
import com.rag.studyhelper.model.Documents;
import com.rag.studyhelper.model.FeishuSyncRun;
import com.rag.studyhelper.service.DocumentIngestionService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class FeishuSyncService {

    private static final Logger log = LoggerFactory.getLogger(FeishuSyncService.class);

    private final FeishuRemoteGateway remote;
    private final DocumentIngestionService ingestion;
    private final FeishuProperties properties;
    private final DocumentsMapper documentsMapper;
    private final FeishuSyncRunMapper runsMapper;
    private final RedissonClient redisson;

    public FeishuSyncService(FeishuRemoteGateway remote,
                             DocumentIngestionService ingestion,
                             FeishuProperties properties,
                             DocumentsMapper documentsMapper,
                             FeishuSyncRunMapper runsMapper,
                             RedissonClient redisson) {
        this.remote = remote;
        this.ingestion = ingestion;
        this.properties = properties;
        this.documentsMapper = documentsMapper;
        this.runsMapper = runsMapper;
        this.redisson = redisson;
        validateConfiguration();
    }

    @Scheduled(cron = "${app.feishu.cron}")
    public FeishuSyncReport syncWiki() {
        FeishuSyncRun run = startRun();
        RLock lock = redisson.getLock("feishu-sync:" + properties.getLocalSpaceId()
                + ":" + properties.getSpaceId());
        boolean acquired = false;
        try {
            acquired = lock.tryLock(properties.getLockWaitSeconds(),
                    properties.getLockLeaseSeconds(), TimeUnit.SECONDS);
            if (!acquired) {
                finish(run, "SKIPPED_LOCKED", false, "Another sync instance holds the lock", null);
                return report(run);
            }
            execute(run);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            resetMissingCounters();
            finish(run, "FAILED", false, null, "Sync lock wait was interrupted");
        } catch (Exception error) {
            resetMissingCounters();
            finish(run, "FAILED", false, null, safeError(error));
            log.error("Feishu sync run failed: runId={}, spaceId={}",
                    run.getId(), properties.getLocalSpaceId(), error);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        return report(run);
    }

    public List<FeishuSyncRun> listRuns(long spaceId) {
        if (spaceId != properties.getLocalSpaceId()) {
            throw new IllegalArgumentException("Feishu sync is not configured for this knowledge space");
        }
        return runsMapper.selectList(Wrappers.<FeishuSyncRun>lambdaQuery()
                .eq(FeishuSyncRun::getSpaceId, spaceId)
                .eq(FeishuSyncRun::getRemoteSpaceId, properties.getSpaceId())
                .orderByDesc(FeishuSyncRun::getStartTime)
                .last("LIMIT 50"));
    }

    private void execute(FeishuSyncRun run) throws IOException {
        FeishuEnumeration enumeration;
        try {
            enumeration = remote.enumerate(properties.getSpaceId());
        } catch (IOException error) {
            resetMissingCounters();
            finish(run, "FAILED", false, null, safeError(error));
            return;
        }
        if (!enumeration.complete()) {
            resetMissingCounters();
            finish(run, "FAILED", false, null, "Remote enumeration was incomplete");
            return;
        }

        run.setEnumerationComplete(true);
        run.setPagesFetched(enumeration.pagesFetched());
        run.setRetryCount(enumeration.retryCount());
        run.setNodesSeen(enumeration.nodes().size());
        run.setRemoteCursor(String.valueOf(enumeration.maxUpdateTime()));

        Set<String> remoteTokens = new HashSet<>();
        for (WikiNode node : enumeration.nodes()) {
            remoteTokens.add(node.getNodeToken());
            syncNode(run, node);
        }

        if (run.getNodesFailed() > 0) {
            resetMissingCounters();
            finish(run, "PARTIAL", true, "Delete phase blocked because one or more nodes failed",
                    run.getErrorSummary());
            return;
        }

        applyProtectedDeletes(run, remoteTokens);
        String status = run.getNodesFailed() > 0 ? "PARTIAL" : "SUCCEEDED";
        finish(run, status, true, run.getGuardReason(), run.getErrorSummary());
    }

    private void syncNode(FeishuSyncRun run, WikiNode node) {
        Documents existing = findRemoteDocument(node.getNodeToken());
        if (!isSupported(node.getObjType())) {
            if (existing != null) {
                markSeen(existing, run.getId());
            }
            incrementSkipped(run);
            return;
        }
        try {
            if (existing != null
                    && FeishuWikiSupport.shouldSkipSync(existing.getFeishuUpdateTime(), node.getUpdateTime())) {
                markSeen(existing, run.getId());
                incrementSkipped(run);
                return;
            }

            String content = remote.readContent(node);
            ingestion.ingestFeishuDocument(
                    properties.getLocalSpaceId(), properties.getSpaceId(),
                    displayName(node), content, node.getNodeToken(),
                    node.getUpdateTime(), node.getObjType());
            Documents indexed = findRemoteDocument(node.getNodeToken());
            if (indexed != null) {
                markSeen(indexed, run.getId());
            }
            if (existing == null) {
                run.setNodesCreated(run.getNodesCreated() + 1);
            } else {
                run.setNodesUpdated(run.getNodesUpdated() + 1);
            }
        } catch (Exception error) {
            run.setNodesFailed(run.getNodesFailed() + 1);
            appendError(run, node.getNodeToken() + ": " + safeError(error));
            log.warn("Feishu node sync failed: runId={}, nodeToken={}, type={}",
                    run.getId(), node.getNodeToken(), node.getObjType());
        }
    }

    private void applyProtectedDeletes(FeishuSyncRun run, Set<String> remoteTokens) {
        List<Documents> local = documentsMapper.selectList(Wrappers.<Documents>lambdaQuery()
                .eq(Documents::getSpaceId, properties.getLocalSpaceId())
                .eq(Documents::getRemoteSpaceId, properties.getSpaceId())
                .eq(Documents::getSource, "FEISHU")
                .notIn(Documents::getStatus, List.of("DELETED", "DELETING")));

        List<Documents> missing = local.stream()
                .filter(document -> !remoteTokens.contains(document.getFeishuNodeToken()))
                .toList();
        run.setDeleteCandidates(missing.size());

        for (Documents document : missing) {
            document.setRemoteMissingCount((document.getRemoteMissingCount() == null
                    ? 0 : document.getRemoteMissingCount()) + 1);
            documentsMapper.updateById(document);
        }

        int confirmations = Math.max(2, properties.getMissingConfirmations());
        List<Documents> eligible = missing.stream()
                .filter(document -> document.getRemoteMissingCount() >= confirmations)
                .toList();
        if (eligible.isEmpty()) {
            return;
        }

        String guard = deletionGuard(local.size(), remoteTokens.size(), eligible.size());
        if (guard != null) {
            run.setDeletesProtected(eligible.size());
            run.setGuardReason(guard);
            return;
        }

        for (Documents document : eligible) {
            try {
                ingestion.deleteDocument(properties.getLocalSpaceId(), document.getId());
                run.setNodesDeleted(run.getNodesDeleted() + 1);
            } catch (Exception error) {
                run.setNodesFailed(run.getNodesFailed() + 1);
                appendError(run, document.getFeishuNodeToken() + ": delete cleanup queued");
            }
        }
    }

    private String deletionGuard(int localCount, int remoteCount, int eligibleCount) {
        if (properties.isProtectZeroRemote() && localCount > 0 && remoteCount == 0) {
            return "Remote enumeration returned zero nodes while local Feishu documents exist";
        }
        if (eligibleCount > Math.max(0, properties.getMaxDeleteCount())) {
            return "Delete candidate count exceeds max-delete-count";
        }
        double ratio = localCount == 0 ? 0d : (double) eligibleCount / localCount;
        if (ratio > Math.max(0d, properties.getMaxDeleteRatio())) {
            return "Delete candidate ratio exceeds max-delete-ratio";
        }
        return null;
    }

    private Documents findRemoteDocument(String nodeToken) {
        return documentsMapper.selectOne(Wrappers.<Documents>lambdaQuery()
                .eq(Documents::getSpaceId, properties.getLocalSpaceId())
                .eq(Documents::getRemoteSpaceId, properties.getSpaceId())
                .eq(Documents::getFeishuNodeToken, nodeToken)
                .ne(Documents::getStatus, "DELETED"));
    }

    private void markSeen(Documents document, long runId) {
        document.setRemoteMissingCount(0);
        document.setLastSeenSyncRunId(runId);
        documentsMapper.updateById(document);
    }

    private void resetMissingCounters() {
        Documents patch = new Documents();
        patch.setRemoteMissingCount(0);
        documentsMapper.update(patch, Wrappers.<Documents>lambdaUpdate()
                .eq(Documents::getSpaceId, properties.getLocalSpaceId())
                .eq(Documents::getRemoteSpaceId, properties.getSpaceId())
                .eq(Documents::getSource, "FEISHU")
                .ne(Documents::getStatus, "DELETED"));
    }

    private FeishuSyncRun startRun() {
        FeishuSyncRun run = new FeishuSyncRun();
        run.setSpaceId(properties.getLocalSpaceId());
        run.setRemoteSpaceId(properties.getSpaceId());
        run.setStatus("RUNNING");
        run.setEnumerationComplete(false);
        run.setPagesFetched(0);
        run.setNodesSeen(0);
        run.setNodesCreated(0);
        run.setNodesUpdated(0);
        run.setNodesSkipped(0);
        run.setNodesFailed(0);
        run.setDeleteCandidates(0);
        run.setNodesDeleted(0);
        run.setDeletesProtected(0);
        run.setRetryCount(0);
        run.setStartTime(LocalDateTime.now());
        runsMapper.insert(run);
        return run;
    }

    private void finish(FeishuSyncRun run, String status, boolean complete,
                        String guardReason, String errorSummary) {
        run.setStatus(status);
        run.setEnumerationComplete(complete);
        run.setGuardReason(abbreviate(guardReason, 500));
        run.setErrorSummary(abbreviate(errorSummary, 1000));
        run.setFinishTime(LocalDateTime.now());
        runsMapper.updateById(run);
    }

    private FeishuSyncReport report(FeishuSyncRun run) {
        return new FeishuSyncReport(
                run.getId(), run.getStatus(), Boolean.TRUE.equals(run.getEnumerationComplete()),
                run.getNodesSeen(), run.getNodesCreated(), run.getNodesUpdated(),
                run.getNodesSkipped(), run.getNodesFailed(), run.getDeleteCandidates(),
                run.getNodesDeleted(), run.getDeletesProtected(), run.getRetryCount(),
                run.getGuardReason(), run.getErrorSummary());
    }

    private void incrementSkipped(FeishuSyncRun run) {
        run.setNodesSkipped(run.getNodesSkipped() + 1);
    }

    private void appendError(FeishuSyncRun run, String message) {
        String current = run.getErrorSummary();
        run.setErrorSummary(abbreviate(current == null ? message : current + "; " + message, 1000));
    }

    private String displayName(WikiNode node) {
        String suffix = switch (node.getObjType()) {
            case "sheet" -> "_sheet";
            case "bitable" -> "_bitable";
            default -> "_document";
        };
        return (node.getNodeTitle() == null || node.getNodeTitle().isBlank()
                ? node.getNodeToken() : node.getNodeTitle()) + suffix;
    }

    private boolean isSupported(String type) {
        return "doc".equals(type) || "docx".equals(type)
                || "sheet".equals(type) || "bitable".equals(type);
    }

    private void validateConfiguration() {
        if (properties.getSpaceId() == null || properties.getSpaceId().isBlank()) {
            throw new IllegalArgumentException("app.feishu.space-id is required when sync is enabled");
        }
        if (properties.getLocalSpaceId() <= 0) {
            throw new IllegalArgumentException("app.feishu.local-space-id must be positive");
        }
        if (properties.getLockLeaseSeconds() <= 0) {
            throw new IllegalArgumentException("app.feishu.lock-lease-seconds must be positive");
        }
    }

    private static String safeError(Throwable error) {
        String message = error.getMessage();
        return abbreviate(message == null ? error.getClass().getSimpleName() : message, 1000);
    }

    private static String abbreviate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
