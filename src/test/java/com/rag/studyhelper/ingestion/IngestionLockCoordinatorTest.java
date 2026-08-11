package com.rag.studyhelper.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionLockCoordinatorTest {

    @Mock
    private RedissonClient redisson;
    @Mock
    private RLock lock;

    @Test
    void acquiresIdentityAndContentLocksAndReleasesBoth() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, 60, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        IngestionLockCoordinator coordinator = new IngestionLockCoordinator(redisson, 0, 60);

        try (IngestionLockCoordinator.Lease ignored = coordinator.acquire(descriptor())) {
            // The protected operation executes while both locks are held.
        }

        verify(lock, times(2)).tryLock(0, 60, TimeUnit.SECONDS);
        verify(lock, times(2)).unlock();
    }

    @Test
    void contentionFailsClosedBeforeAnyWriteCanStart() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, 60, TimeUnit.SECONDS)).thenReturn(false);
        IngestionLockCoordinator coordinator = new IngestionLockCoordinator(redisson, 0, 60);

        assertThrows(IllegalStateException.class, () -> coordinator.acquire(descriptor()));
    }

    private DocumentDescriptor descriptor() {
        return new DocumentDescriptor(3L, "guide.md", "md", "text/markdown",
                "UPLOAD", "content-hash", 10L, null,
                null, null, null, 0L, "local");
    }
}
