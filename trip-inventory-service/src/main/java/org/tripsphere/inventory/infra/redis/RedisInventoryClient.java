package org.tripsphere.inventory.infra.redis;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.tripsphere.inventory.model.DailyInventoryEntity;

/**
 * Redis operations for inventory management. Redis serves as a <b>read cache</b> only — MySQL is
 * the source of truth for all writes. Cache updates are best-effort after MySQL commits.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisInventoryClient {

    private final StringRedisTemplate redisTemplate;

    private static final String INV_KEY_PREFIX = "inv:";
    private static final String LOCK_EXPIRY_KEY = "inv:lock:expiry";
    private static final String MUTEX_KEY_PREFIX = "inv:mutex:";

    // ===================================================================
    // Inventory Cache Operations
    // ===================================================================

    /** Build the Redis key for a given SKU and date. */
    public String buildKey(String skuId, LocalDate date) {
        return INV_KEY_PREFIX + skuId + ":" + date.toString();
    }

    /** Cache a daily inventory record in Redis (best-effort, called after MySQL commit). */
    public void cacheInventory(DailyInventoryEntity entity) {
        try {
            String key = buildKey(entity.getSkuId(), entity.getInvDate());
            Map<String, String> hash = new HashMap<>();
            hash.put("total", String.valueOf(entity.getTotalQty()));
            hash.put("available", String.valueOf(entity.getAvailableQty()));
            hash.put("locked", String.valueOf(entity.getLockedQty()));
            hash.put("sold", String.valueOf(entity.getSoldQty()));
            hash.put("price_units", String.valueOf(entity.getPriceUnits()));
            hash.put("price_nanos", String.valueOf(entity.getPriceNanos()));
            hash.put("price_ccy", entity.getPriceCurrency());
            redisTemplate.opsForHash().putAll(key, hash);
            redisTemplate.expire(key, 7, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn(
                    "Failed to cache inventory for sku={}, date={}: {}",
                    entity.getSkuId(),
                    entity.getInvDate(),
                    e.getMessage());
        }
    }

    /** Get cached inventory. Returns null if not cached. */
    public Map<String, String> getCachedInventory(String skuId, LocalDate date) {
        String key = buildKey(skuId, date);
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) return null;
        Map<String, String> result = new HashMap<>();
        entries.forEach((k, v) -> result.put(k.toString(), v.toString()));
        return result;
    }

    /**
     * Try to acquire a short-lived mutex for cache rebuild, preventing thundering-herd on cache
     * miss. Returns true if this caller should rebuild the cache; false if another thread is
     * already doing it.
     */
    public boolean tryAcquireCacheMutex(String skuId, LocalDate date) {
        String mutexKey = MUTEX_KEY_PREFIX + skuId + ":" + date.toString();
        Boolean acquired =
                redisTemplate.opsForValue().setIfAbsent(mutexKey, "1", 5, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    /** Delete cached inventory. */
    public void evictInventory(String skuId, LocalDate date) {
        String key = buildKey(skuId, date);
        redisTemplate.delete(key);
    }

    // ===================================================================
    // Batch Cache Sync (called after MySQL transaction commits)
    // ===================================================================

    /**
     * Atomically sync multiple inventory cache entries from committed MySQL state. Uses a Lua
     * script so the hash updates are atomic per key.
     */
    public void batchSyncCache(List<DailyInventoryEntity> entities) {
        for (DailyInventoryEntity entity : entities) {
            cacheInventory(entity);
        }
    }

    // ===================================================================
    // Lock Expiry Sorted Set
    // ===================================================================

    /** Add a lock to the expiry sorted set. */
    public void addLockExpiry(String lockId, long expireTimestamp) {
        try {
            redisTemplate.opsForZSet().add(LOCK_EXPIRY_KEY, lockId, expireTimestamp);
        } catch (Exception e) {
            log.warn("Failed to add lock expiry for lockId={}: {}", lockId, e.getMessage());
        }
    }

    /** Remove a lock from the expiry sorted set. */
    public void removeLockExpiry(String lockId) {
        try {
            redisTemplate.opsForZSet().remove(LOCK_EXPIRY_KEY, lockId);
        } catch (Exception e) {
            log.warn("Failed to remove lock expiry for lockId={}: {}", lockId, e.getMessage());
        }
    }

    /**
     * Get a batch of expired lock IDs (score &le; now), limited to {@code batchSize} to avoid
     * blocking Redis with a huge scan.
     */
    public Set<String> getExpiredLockIds(long now, int batchSize) {
        return redisTemplate.opsForZSet().rangeByScore(LOCK_EXPIRY_KEY, 0, now, 0, batchSize);
    }
}
