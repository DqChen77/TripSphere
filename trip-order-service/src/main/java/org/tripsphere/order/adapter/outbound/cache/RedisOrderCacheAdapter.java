package org.tripsphere.order.adapter.outbound.cache;

import java.time.Duration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.tripsphere.order.application.port.OrderCachePort;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisOrderCacheAdapter implements OrderCachePort {

    private final StringRedisTemplate redisTemplate;

    private static final String ORDER_EXPIRE_KEY = "order:expire";
    private static final String ORDER_DEDUP_KEY_PREFIX = "order:dedup:";
    private static final Duration DEDUP_WINDOW = Duration.ofSeconds(10);

    @Override
    public boolean tryAcquireDedup(String userId, String fingerprint) {
        try {
            String dedupKey = ORDER_DEDUP_KEY_PREFIX + userId + ":" + Integer.toHexString(fingerprint.hashCode());
            Boolean isNew = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", DEDUP_WINDOW);
            return Boolean.TRUE.equals(isNew);
        } catch (Exception e) {
            log.warn("Redis dedup check failed, proceeding without dedup: {}", e.getMessage());
            return true;
        }
    }

    @Override
    public void addOrderExpiry(String orderId, long expireTimestamp) {
        try {
            redisTemplate.opsForZSet().add(ORDER_EXPIRE_KEY, orderId, expireTimestamp);
        } catch (Exception e) {
            log.warn("Failed to add order expiry to Redis: {}", e.getMessage());
        }
    }

    @Override
    public void removeOrderExpiry(String orderId) {
        try {
            redisTemplate.opsForZSet().remove(ORDER_EXPIRE_KEY, orderId);
        } catch (Exception e) {
            log.warn("Failed to remove order expiry from Redis: {}", e.getMessage());
        }
    }

    @Override
    public Set<String> getExpiredOrderIds(long now, int batchSize) {
        return redisTemplate.opsForZSet().rangeByScore(ORDER_EXPIRE_KEY, 0, now, 0, batchSize);
    }
}
