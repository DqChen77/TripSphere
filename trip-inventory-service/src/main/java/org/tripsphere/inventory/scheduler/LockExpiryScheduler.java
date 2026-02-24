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
 * Scheduled task to release expired inventory locks. Runs every 30 seconds, checking the Redis
 * sorted set for expired locks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LockExpiryScheduler {

    private final RedisInventoryClient redisClient;
    private final InventoryService inventoryService;

    @Scheduled(fixedDelay = 30000)
    public void releaseExpiredLocks() {
        long now = Instant.now().getEpochSecond();
        Set<String> expiredLockIds = redisClient.getExpiredLockIds(now);

        if (expiredLockIds == null || expiredLockIds.isEmpty()) {
            return;
        }

        log.info("Found {} expired locks to release", expiredLockIds.size());

        for (String lockId : expiredLockIds) {
            try {
                inventoryService.releaseLock(lockId, "Lock expired (auto-release)");
                log.info("Released expired lock: {}", lockId);
            } catch (Exception e) {
                log.error("Failed to release expired lock: {}", lockId, e);
                // Remove from sorted set to avoid repeated failures
                redisClient.removeLockExpiry(lockId);
            }
        }
    }
}
