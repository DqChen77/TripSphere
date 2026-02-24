package org.tripsphere.inventory.infra.redis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.tripsphere.inventory.model.DailyInventoryEntity;

/**
 * Redis operations for inventory management. Uses Redis as a cache layer and for atomic lock
 * operations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisInventoryClient {

    private final StringRedisTemplate redisTemplate;

    private static final String INV_KEY_PREFIX = "inv:";
    private static final String LOCK_EXPIRY_KEY = "inv:lock:expiry";

    // ===================================================================
    // Inventory Cache Operations
    // ===================================================================

    /** Build the Redis key for a given SKU and date. */
    public String buildKey(String skuId, LocalDate date) {
        return INV_KEY_PREFIX + skuId + ":" + date.toString();
    }

    /** Cache a daily inventory record in Redis. */
    public void cacheInventory(DailyInventoryEntity entity) {
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
        // Set expiry to 7 days
        redisTemplate.expire(key, 7, TimeUnit.DAYS);
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

    /** Delete cached inventory. */
    public void evictInventory(String skuId, LocalDate date) {
        String key = buildKey(skuId, date);
        redisTemplate.delete(key);
    }

    // ===================================================================
    // Atomic Lock Operations (Lua Scripts)
    // ===================================================================

    /**
     * Atomically lock inventory for multiple (sku, date, qty) tuples. All items succeed or all
     * fail.
     *
     * @return true if all locks succeeded, false if any item has insufficient inventory
     */
    public boolean lockInventoryAtomic(
            List<String> skuIds, List<LocalDate> dates, List<Integer> quantities) {
        String luaScript =
                """
                local n = tonumber(ARGV[1])
                for i = 1, n do
                    local key = KEYS[i]
                    local qty = tonumber(ARGV[i + 1])
                    local available = tonumber(redis.call('HGET', key, 'available') or '0')
                    if available < qty then
                        return 0
                    end
                end
                for i = 1, n do
                    local key = KEYS[i]
                    local qty = tonumber(ARGV[i + 1])
                    redis.call('HINCRBY', key, 'available', -qty)
                    redis.call('HINCRBY', key, 'locked', qty)
                end
                return 1
                """;

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(luaScript, Long.class);

        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(skuIds.size()));

        for (int i = 0; i < skuIds.size(); i++) {
            keys.add(buildKey(skuIds.get(i), dates.get(i)));
            args.add(String.valueOf(quantities.get(i)));
        }

        Long result = redisTemplate.execute(script, keys, (Object[]) args.toArray(new String[0]));
        return result != null && result == 1L;
    }

    /** Atomically confirm locked inventory (locked -> sold). */
    public void confirmLockAtomic(
            List<String> skuIds, List<LocalDate> dates, List<Integer> quantities) {
        String luaScript =
                """
                local n = tonumber(ARGV[1])
                for i = 1, n do
                    local key = KEYS[i]
                    local qty = tonumber(ARGV[i + 1])
                    redis.call('HINCRBY', key, 'locked', -qty)
                    redis.call('HINCRBY', key, 'sold', qty)
                end
                return 1
                """;

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(luaScript, Long.class);

        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(skuIds.size()));

        for (int i = 0; i < skuIds.size(); i++) {
            keys.add(buildKey(skuIds.get(i), dates.get(i)));
            args.add(String.valueOf(quantities.get(i)));
        }

        redisTemplate.execute(script, keys, (Object[]) args.toArray(new String[0]));
    }

    /** Atomically release locked inventory (locked -> available). */
    public void releaseLockAtomic(
            List<String> skuIds, List<LocalDate> dates, List<Integer> quantities) {
        String luaScript =
                """
                local n = tonumber(ARGV[1])
                for i = 1, n do
                    local key = KEYS[i]
                    local qty = tonumber(ARGV[i + 1])
                    redis.call('HINCRBY', key, 'locked', -qty)
                    redis.call('HINCRBY', key, 'available', qty)
                end
                return 1
                """;

        DefaultRedisScript<Long> script = new DefaultRedisScript<>(luaScript, Long.class);

        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(skuIds.size()));

        for (int i = 0; i < skuIds.size(); i++) {
            keys.add(buildKey(skuIds.get(i), dates.get(i)));
            args.add(String.valueOf(quantities.get(i)));
        }

        redisTemplate.execute(script, keys, (Object[]) args.toArray(new String[0]));
    }

    // ===================================================================
    // Lock Expiry Sorted Set
    // ===================================================================

    /** Add a lock to the expiry sorted set. */
    public void addLockExpiry(String lockId, long expireTimestamp) {
        redisTemplate.opsForZSet().add(LOCK_EXPIRY_KEY, lockId, expireTimestamp);
    }

    /** Remove a lock from the expiry sorted set. */
    public void removeLockExpiry(String lockId) {
        redisTemplate.opsForZSet().remove(LOCK_EXPIRY_KEY, lockId);
    }

    /** Get all expired lock IDs (score <= now). */
    public java.util.Set<String> getExpiredLockIds(long now) {
        return redisTemplate.opsForZSet().rangeByScore(LOCK_EXPIRY_KEY, 0, now);
    }
}
