package org.tripsphere.inventory.scheduler;

import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tripsphere.inventory.infra.redis.RedisInventoryClient;
import org.tripsphere.inventory.service.InventoryService;

/**
 * Scheduled task to release expired inventory locks. Runs every 30 seconds.
 *
 * <p>Race-safety: {@link InventoryService#releaseLock} is idempotent — if the lock was already
 * confirmed (payment succeeded between expiry scan and release attempt), the confirm wins at the
 * MySQL level (status is CONFIRMED, release is rejected gracefully). If the lock was already
 * released by another scheduler instance, the idempotent check returns immediately.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LockExpiryScheduler {

    private final RedisInventoryClient redisClient;
    private final InventoryService inventoryService;

    /** Max locks to process per scheduler tick to avoid blocking. */
    private static final int BATCH_SIZE = 50;

    @Scheduled(fixedDelay = 30000)
    public void releaseExpiredLocks() {
        long now = Instant.now().getEpochSecond();
        Set<String> expiredLockIds = redisClient.getExpiredLockIds(now, BATCH_SIZE);

        if (expiredLockIds == null || expiredLockIds.isEmpty()) {
            return;
        }

        log.info(
                "Found {} expired locks to release (batch limit {})",
                expiredLockIds.size(),
                BATCH_SIZE);

        for (String lockId : expiredLockIds) {
            try {
                // releaseLock is idempotent: if lock was already confirmed / released,
                // it returns gracefully without side effects.
                inventoryService.releaseLock(lockId, "Lock expired (auto-release)");
                log.info("Released expired lock: {}", lockId);
            } catch (Exception e) {
                log.warn("Failed to release expired lock {}: {}", lockId, e.getMessage());
            }
            // Always remove from sorted set — either the release succeeded,
            // or the lock is in a terminal state (CONFIRMED/RELEASED) and no longer needs expiry.
            redisClient.removeLockExpiry(lockId);
        }
    }
}
