package org.tripsphere.inventory.adapter.inbound.scheduler;

import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tripsphere.inventory.application.port.InventoryCachePort;
import org.tripsphere.inventory.application.service.command.ReleaseLockUseCase;

@Slf4j
@Component
@RequiredArgsConstructor
public class LockExpiryScheduler {

    private final InventoryCachePort cachePort;
    private final ReleaseLockUseCase releaseLockUseCase;

    private static final int BATCH_SIZE = 50;

    @Scheduled(fixedDelay = 30000)
    public void releaseExpiredLocks() {
        long now = Instant.now().getEpochSecond();
        Set<String> expiredLockIds = cachePort.getExpiredLockIds(now, BATCH_SIZE);

        if (expiredLockIds == null || expiredLockIds.isEmpty()) {
            return;
        }

        log.info("Found {} expired locks to release (batch limit {})", expiredLockIds.size(), BATCH_SIZE);

        for (String lockId : expiredLockIds) {
            try {
                releaseLockUseCase.execute(lockId, "Lock expired (auto-release)");
                log.info("Released expired lock: {}", lockId);
            } catch (Exception e) {
                log.warn("Failed to release expired lock {}: {}", lockId, e.getMessage());
            }
            cachePort.removeLockExpiry(lockId);
        }
    }
}
