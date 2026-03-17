package org.tripsphere.inventory.application.port;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import org.tripsphere.inventory.domain.model.DailyInventory;

public interface InventoryCachePort {

    void cacheDailyInventory(DailyInventory inventory);

    Optional<DailyInventory> getCachedInventory(String skuId, LocalDate date);

    boolean tryAcquireCacheMutex(String skuId, LocalDate date);

    void addLockExpiry(String lockId, long expireTimestamp);

    void removeLockExpiry(String lockId);

    Set<String> getExpiredLockIds(long now, int batchSize);
}
