package org.tripsphere.inventory.scheduler;

import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tripsphere.inventory.infra.redis.RedisInventoryClient;
import org.tripsphere.inventory.service.InventoryService;

/** Releases expired inventory locks every 30 seconds. releaseLock is idempotent. */
@Slf4j
@Component
@RequiredArgsConstructor
public class LockExpiryScheduler {

    private final RedisInventoryClient redisClient;
    private final InventoryService inventoryService;

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
                inventoryService.releaseLock(lockId, "Lock expired (auto-release)");
                log.info("Released expired lock: {}", lockId);
            } catch (Exception e) {
                log.warn("Failed to release expired lock {}: {}", lockId, e.getMessage());
            }
            redisClient.removeLockExpiry(lockId);
        }
    }
}
