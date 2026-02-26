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

/** Redis read cache for inventory. MySQL is the source of truth. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisInventoryClient {

    private final StringRedisTemplate redisTemplate;

    private static final String INV_KEY_PREFIX = "inv:";
    private static final String LOCK_EXPIRY_KEY = "inv:lock:expiry";
    private static final String MUTEX_KEY_PREFIX = "inv:mutex:";

    public String buildKey(String skuId, LocalDate date) {
        return INV_KEY_PREFIX + skuId + ":" + date.toString();
    }

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

    public Map<String, String> getCachedInventory(String skuId, LocalDate date) {
        String key = buildKey(skuId, date);
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) return null;
        Map<String, String> result = new HashMap<>();
        entries.forEach((k, v) -> result.put(k.toString(), v.toString()));
        return result;
    }

    /** Mutex to prevent thundering-herd on cache miss. */
    public boolean tryAcquireCacheMutex(String skuId, LocalDate date) {
        String mutexKey = MUTEX_KEY_PREFIX + skuId + ":" + date.toString();
        Boolean acquired =
                redisTemplate.opsForValue().setIfAbsent(mutexKey, "1", 5, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired);
    }

    public void evictInventory(String skuId, LocalDate date) {
        String key = buildKey(skuId, date);
        redisTemplate.delete(key);
    }

    public void batchSyncCache(List<DailyInventoryEntity> entities) {
        for (DailyInventoryEntity entity : entities) {
            cacheInventory(entity);
        }
    }

    public void addLockExpiry(String lockId, long expireTimestamp) {
        try {
            redisTemplate.opsForZSet().add(LOCK_EXPIRY_KEY, lockId, expireTimestamp);
        } catch (Exception e) {
            log.warn("Failed to add lock expiry for lockId={}: {}", lockId, e.getMessage());
        }
    }

    public void removeLockExpiry(String lockId) {
        try {
            redisTemplate.opsForZSet().remove(LOCK_EXPIRY_KEY, lockId);
        } catch (Exception e) {
            log.warn("Failed to remove lock expiry for lockId={}: {}", lockId, e.getMessage());
        }
    }

    public Set<String> getExpiredLockIds(long now, int batchSize) {
        return redisTemplate.opsForZSet().rangeByScore(LOCK_EXPIRY_KEY, 0, now, 0, batchSize);
    }
}
