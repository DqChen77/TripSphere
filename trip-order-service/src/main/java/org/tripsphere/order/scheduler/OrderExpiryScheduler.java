package org.tripsphere.order.scheduler;

import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tripsphere.order.service.OrderService;

/**
 * Scheduled task to auto-cancel expired orders. Runs every 30 seconds, checking the Redis sorted
 * set for expired orders.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpiryScheduler {

    private final StringRedisTemplate redisTemplate;
    private final OrderService orderService;

    private static final String ORDER_EXPIRE_KEY = "order:expire";

    /** Max orders to process per scheduler tick to avoid blocking. */
    private static final int BATCH_SIZE = 50;

    @Scheduled(fixedDelay = 30000)
    public void cancelExpiredOrders() {
        long now = Instant.now().getEpochSecond();
        Set<String> expiredOrderIds =
                redisTemplate.opsForZSet().rangeByScore(ORDER_EXPIRE_KEY, 0, now, 0, BATCH_SIZE);

        if (expiredOrderIds == null || expiredOrderIds.isEmpty()) {
            return;
        }

        log.info("Found {} expired orders to cancel", expiredOrderIds.size());

        for (String orderId : expiredOrderIds) {
            try {
                orderService.cancelOrder(orderId, "Payment timeout - auto cancelled");
                log.info("Auto-cancelled expired order: {}", orderId);
            } catch (Exception e) {
                log.error("Failed to cancel expired order: {}", orderId, e);
                // Remove from sorted set to avoid repeated failures
                redisTemplate.opsForZSet().remove(ORDER_EXPIRE_KEY, orderId);
            }
        }
    }
}
