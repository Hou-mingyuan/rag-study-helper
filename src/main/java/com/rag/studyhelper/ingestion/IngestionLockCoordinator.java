package com.rag.studyhelper.ingestion;

import com.rag.studyhelper.utils.Hashing;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Coordinates document identity and content-hash writes across app instances. */
@Component
public class IngestionLockCoordinator {

    private static final Logger log = LoggerFactory.getLogger(IngestionLockCoordinator.class);

    private final RedissonClient redisson;
    private final long waitSeconds;
    private final long leaseSeconds;

    public IngestionLockCoordinator(
            RedissonClient redisson,
            @Value("${app.rag.ingestion-lock-wait-seconds:0}") long waitSeconds,
            @Value("${app.rag.ingestion-lock-lease-seconds:300}") long leaseSeconds) {
        this.redisson = redisson;
        this.waitSeconds = Math.max(0, waitSeconds);
        this.leaseSeconds = Math.max(30, leaseSeconds);
    }

    public Lease acquire(DocumentDescriptor descriptor) {
        List<String> names = lockNames(descriptor);
        List<RLock> acquired = new ArrayList<>(names.size());
        try {
            for (String name : names) {
                RLock lock = redisson.getLock(name);
                if (!lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Another ingestion is already updating this document");
                }
                acquired.add(lock);
            }
            return () -> release(acquired);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            release(acquired);
            throw new IllegalStateException("Document ingestion lock was interrupted", interrupted);
        } catch (RuntimeException error) {
            release(acquired);
            throw error;
        }
    }

    private List<String> lockNames(DocumentDescriptor descriptor) {
        String identity = "FEISHU".equals(descriptor.source())
                ? nullSafe(descriptor.remoteSpaceId()) + ":" + nullSafe(descriptor.feishuNodeToken())
                : nullSafe(descriptor.documentName()).toLowerCase(Locale.ROOT);
        Set<String> names = new LinkedHashSet<>();
        names.add(lockName(descriptor.spaceId(), "identity", descriptor.source() + ":" + identity));
        if (descriptor.contentHash() != null && !descriptor.contentHash().isBlank()) {
            names.add(lockName(descriptor.spaceId(), "content", descriptor.contentHash()));
        }
        return names.stream().sorted().toList();
    }

    private String lockName(long spaceId, String kind, String value) {
        return "rag:ingestion:" + spaceId + ":" + kind + ":" + Hashing.sha256(value);
    }

    private void release(List<RLock> acquired) {
        for (int index = acquired.size() - 1; index >= 0; index--) {
            RLock lock = acquired.get(index);
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (RuntimeException error) {
                log.warn("Ingestion lock release failed: errorType={}",
                        error.getClass().getSimpleName());
            }
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    @FunctionalInterface
    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
