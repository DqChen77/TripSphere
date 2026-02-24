package org.tripsphere.order.service.impl;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tripsphere.order.exception.NotFoundException;
import org.tripsphere.order.exception.OrderStateException;
import org.tripsphere.order.grpc.client.InventoryServiceClient;
import org.tripsphere.order.mapper.OrderMapper;
import org.tripsphere.order.model.OrderEntity;
import org.tripsphere.order.model.OrderItemEntity;
import org.tripsphere.order.repository.OrderItemRepository;
import org.tripsphere.order.repository.OrderRepository;
import org.tripsphere.order.saga.CreateOrderSaga;
import org.tripsphere.order.service.OrderService;
import org.tripsphere.order.v1.ContactInfo;
import org.tripsphere.order.v1.CreateOrderItem;
import org.tripsphere.order.v1.Order;
import org.tripsphere.order.v1.OrderSource;
import org.tripsphere.order.v1.OrderStatus;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CreateOrderSaga createOrderSaga;
    private final InventoryServiceClient inventoryClient;
    private final StringRedisTemplate redisTemplate;
    private final OrderMapper orderMapper = OrderMapper.INSTANCE;

    private static final String ORDER_SEQ_KEY_PREFIX = "order:seq:";
    private static final String ORDER_EXPIRE_KEY = "order:expire";

    // ===================================================================
    // Create Order
    // ===================================================================

    @Override
    @Transactional
    public Order createOrder(
            String userId, List<CreateOrderItem> items, ContactInfo contact, OrderSource source) {
        log.info("Creating order for user: {}, items: {}", userId, items.size());

        // Generate order number
        String orderNo = generateOrderNo();

        // Execute Saga
        OrderEntity order = createOrderSaga.execute(userId, items, contact, source, orderNo);

        // Add to Redis expiry sorted set
        if (order.getExpireAt() != null) {
            redisTemplate.opsForZSet().add(ORDER_EXPIRE_KEY, order.getId(), order.getExpireAt());
        }

        return orderMapper.toProto(order);
    }

    // ===================================================================
    // Get Order
    // ===================================================================

    @Override
    public Optional<Order> getOrder(String orderId) {
        log.debug("Getting order: {}", orderId);
        return orderRepository
                .findById(orderId)
                .map(
                        entity -> {
                            List<OrderItemEntity> items =
                                    orderItemRepository.findByOrderId(orderId);
                            entity.setItems(items);
                            return orderMapper.toProto(entity);
                        });
    }

    // ===================================================================
    // List User Orders
    // ===================================================================

    @Override
    public Page<Order> listUserOrders(
            String userId, OrderStatus status, int pageSize, String pageToken) {
        log.debug(
                "Listing orders: userId={}, status={}, pageSize={}, pageToken={}",
                userId,
                status,
                pageSize,
                pageToken);

        int page = 0;
        if (pageToken != null && !pageToken.isEmpty()) {
            try {
                page = Integer.parseInt(pageToken);
            } catch (NumberFormatException e) {
                log.warn("Invalid page token: {}", pageToken);
            }
        }
        if (pageSize <= 0) pageSize = 20;
        if (pageSize > 100) pageSize = 100;

        Page<OrderEntity> entityPage;
        if (status != null && status != OrderStatus.ORDER_STATUS_UNSPECIFIED) {
            String statusStr = orderMapper.orderStatusToString(status);
            entityPage =
                    orderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(
                            userId, statusStr, PageRequest.of(page, pageSize));
        } else {
            entityPage =
                    orderRepository.findByUserIdOrderByCreatedAtDesc(
                            userId, PageRequest.of(page, pageSize));
        }

        return entityPage.map(
                entity -> {
                    List<OrderItemEntity> items = orderItemRepository.findByOrderId(entity.getId());
                    entity.setItems(items);
                    return orderMapper.toProto(entity);
                });
    }

    // ===================================================================
    // Cancel Order
    // ===================================================================

    @Override
    @Transactional
    public Order cancelOrder(String orderId, String reason) {
        log.info("Cancelling order: {}, reason: {}", orderId, reason);

        OrderEntity order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(() -> new NotFoundException("Order", orderId));

        if (!"PENDING_PAYMENT".equals(order.getStatus())) {
            throw new OrderStateException(orderId, order.getStatus(), "PENDING_PAYMENT");
        }

        // Release inventory locks
        List<OrderItemEntity> items = orderItemRepository.findByOrderId(orderId);
        for (OrderItemEntity item : items) {
            if (item.getInvLockId() != null && !item.getInvLockId().isEmpty()) {
                try {
                    inventoryClient.releaseLock(item.getInvLockId(), reason);
                } catch (Exception e) {
                    log.error(
                            "Failed to release inventory lock: {} for order: {}",
                            item.getInvLockId(),
                            orderId,
                            e);
                }
            }
        }

        // Update order status
        long now = Instant.now().getEpochSecond();
        order.setStatus("CANCELLED");
        order.setCancelReason(reason);
        order.setCancelledAt(now);
        order.setUpdatedAt(now);
        order = orderRepository.save(order);
        order.setItems(items);

        // Remove from expiry sorted set
        redisTemplate.opsForZSet().remove(ORDER_EXPIRE_KEY, orderId);

        log.info("Order cancelled: {}", orderId);
        return orderMapper.toProto(order);
    }

    // ===================================================================
    // Confirm Payment
    // ===================================================================

    @Override
    @Transactional
    public Order confirmPayment(String orderId, String paymentMethod) {
        log.info("Confirming payment for order: {}, method: {}", orderId, paymentMethod);

        OrderEntity order =
                orderRepository
                        .findById(orderId)
                        .orElseThrow(() -> new NotFoundException("Order", orderId));

        if (!"PENDING_PAYMENT".equals(order.getStatus())) {
            throw new OrderStateException(orderId, order.getStatus(), "PENDING_PAYMENT");
        }

        // Check if order has expired
        long now = Instant.now().getEpochSecond();
        if (order.getExpireAt() != null && now > order.getExpireAt()) {
            throw new OrderStateException("Order " + orderId + " has expired");
        }

        // Confirm inventory locks
        List<OrderItemEntity> items = orderItemRepository.findByOrderId(orderId);
        for (OrderItemEntity item : items) {
            if (item.getInvLockId() != null && !item.getInvLockId().isEmpty()) {
                try {
                    inventoryClient.confirmLock(item.getInvLockId());
                } catch (Exception e) {
                    log.error(
                            "Failed to confirm inventory lock: {} for order: {}",
                            item.getInvLockId(),
                            orderId,
                            e);
                    throw new RuntimeException(
                            "Failed to confirm inventory for order: " + orderId, e);
                }
            }
        }

        // Update order status
        order.setStatus("PAID");
        order.setPaidAt(now);
        order.setUpdatedAt(now);
        order = orderRepository.save(order);
        order.setItems(items);

        // Remove from expiry sorted set
        redisTemplate.opsForZSet().remove(ORDER_EXPIRE_KEY, orderId);

        log.info("Payment confirmed for order: {}", orderId);
        return orderMapper.toProto(order);
    }

    // ===================================================================
    // Order Number Generation
    // ===================================================================

    /**
     * Generate order number: TS + yyyyMMdd + 6-digit sequence. Uses Redis INCR for daily sequence.
     */
    private String generateOrderNo() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String seqKey = ORDER_SEQ_KEY_PREFIX + today;
        Long seq = redisTemplate.opsForValue().increment(seqKey);
        if (seq != null && seq == 1) {
            // Set expiry for the daily sequence key (2 days)
            redisTemplate.expire(seqKey, java.time.Duration.ofDays(2));
        }
        return String.format("TS%s%06d", today, seq != null ? seq : 1);
    }
}
