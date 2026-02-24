package org.tripsphere.order.saga;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tripsphere.common.v1.Date;
import org.tripsphere.common.v1.Money;
import org.tripsphere.inventory.v1.DailyInventory;
import org.tripsphere.inventory.v1.InventoryLock;
import org.tripsphere.inventory.v1.LockItem;
import org.tripsphere.order.exception.InvalidArgumentException;
import org.tripsphere.order.grpc.client.InventoryServiceClient;
import org.tripsphere.order.grpc.client.ProductServiceClient;
import org.tripsphere.order.mapper.OrderMapper;
import org.tripsphere.order.model.OrderEntity;
import org.tripsphere.order.model.OrderItemEntity;
import org.tripsphere.order.repository.OrderItemRepository;
import org.tripsphere.order.repository.OrderRepository;
import org.tripsphere.order.v1.ContactInfo;
import org.tripsphere.order.v1.CreateOrderItem;
import org.tripsphere.order.v1.OrderSource;
import org.tripsphere.product.v1.SkuStatus;
import org.tripsphere.product.v1.StockKeepingUnit;

/**
 * Saga Orchestrator for CreateOrder. Coordinates: Product validation → Inventory locking → Order
 * persistence. Includes compensation (inventory release) on failure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreateOrderSaga {

    private final ProductServiceClient productClient;
    private final InventoryServiceClient inventoryClient;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderMapper orderMapper = OrderMapper.INSTANCE;

    private static final int ORDER_EXPIRE_SECONDS = 900; // 15 minutes

    /** Execute the CreateOrder saga. */
    public OrderEntity execute(
            String userId,
            List<CreateOrderItem> items,
            ContactInfo contact,
            OrderSource source,
            String orderNo) {

        log.info("Starting CreateOrder saga for user: {}, items: {}", userId, items.size());

        // ============================================================
        // Step 1: Validate SKUs via Product Service
        // ============================================================
        List<String> skuIds = items.stream().map(CreateOrderItem::getSkuId).distinct().toList();
        List<StockKeepingUnit> skus = productClient.batchGetSkus(skuIds);

        Map<String, StockKeepingUnit> skuMap =
                skus.stream().collect(Collectors.toMap(StockKeepingUnit::getId, sku -> sku));

        // Validate all requested SKUs exist and are active
        for (String skuId : skuIds) {
            StockKeepingUnit sku = skuMap.get(skuId);
            if (sku == null) {
                throw new InvalidArgumentException("SKU not found: " + skuId);
            }
            if (sku.getStatus() != SkuStatus.SKU_STATUS_ACTIVE) {
                throw new InvalidArgumentException("SKU is not active: " + skuId);
            }
        }

        // ============================================================
        // Step 2: Lock Inventory
        // ============================================================
        String orderId = UUID.randomUUID().toString();
        List<LockItem> lockItems = new ArrayList<>();
        for (CreateOrderItem item : items) {
            LockItem.Builder lockItem =
                    LockItem.newBuilder()
                            .setSkuId(item.getSkuId())
                            .setDate(item.getDate())
                            .setQuantity(item.getQuantity());
            lockItems.add(lockItem.build());
        }

        InventoryLock inventoryLock;
        try {
            inventoryLock = inventoryClient.lockInventory(lockItems, orderId, ORDER_EXPIRE_SECONDS);
        } catch (Exception e) {
            log.error("Failed to lock inventory for order: {}", orderId, e);
            throw e;
        }

        // ============================================================
        // Step 3: Create Order locally
        // ============================================================
        try {
            long now = Instant.now().getEpochSecond();

            // Build order items with snapshots and prices
            List<OrderItemEntity> orderItems = new ArrayList<>();
            long totalUnits = 0;
            int totalNanos = 0;
            String totalCurrency = "CNY";

            for (CreateOrderItem createItem : items) {
                StockKeepingUnit sku = skuMap.get(createItem.getSkuId());
                String itemId = UUID.randomUUID().toString();

                // Get actual price from inventory for this date
                Money unitPrice = getUnitPrice(createItem.getSkuId(), createItem.getDate(), sku);
                long subtotalUnits = unitPrice.getUnits() * createItem.getQuantity();
                int subtotalNanos = unitPrice.getNanos() * createItem.getQuantity();
                // Handle nanos overflow
                subtotalUnits += subtotalNanos / 1_000_000_000;
                subtotalNanos = subtotalNanos % 1_000_000_000;

                totalCurrency = unitPrice.getCurrency().isEmpty() ? "CNY" : unitPrice.getCurrency();
                totalUnits += subtotalUnits;
                totalNanos += subtotalNanos;

                LocalDate itemDate = orderMapper.protoToLocalDate(createItem.getDate());
                LocalDate endDate =
                        createItem.hasEndDate()
                                ? orderMapper.protoToLocalDate(createItem.getEndDate())
                                : null;

                OrderItemEntity orderItem =
                        OrderItemEntity.builder()
                                .id(itemId)
                                .orderId(orderId)
                                .spuId(sku.getSpuId())
                                .skuId(sku.getId())
                                .productName(getProductName(sku))
                                .skuName(sku.getName())
                                .itemDate(itemDate)
                                .endDate(endDate)
                                .quantity(createItem.getQuantity())
                                .unitPriceCcy(totalCurrency)
                                .unitPriceUnits(unitPrice.getUnits())
                                .unitPriceNanos(unitPrice.getNanos())
                                .subtotalCcy(totalCurrency)
                                .subtotalUnits(subtotalUnits)
                                .subtotalNanos(subtotalNanos)
                                .invLockId(inventoryLock.getLockId())
                                .build();
                orderItems.add(orderItem);
            }

            // Handle nanos overflow for total
            totalUnits += totalNanos / 1_000_000_000;
            totalNanos = totalNanos % 1_000_000_000;

            OrderEntity order =
                    OrderEntity.builder()
                            .id(orderId)
                            .orderNo(orderNo)
                            .userId(userId)
                            .status("PENDING_PAYMENT")
                            .totalCurrency(totalCurrency)
                            .totalUnits(totalUnits)
                            .totalNanos(totalNanos)
                            .contactName(contact != null ? contact.getName() : null)
                            .contactPhone(contact != null ? contact.getPhone() : null)
                            .contactEmail(contact != null ? contact.getEmail() : null)
                            .sourceChannel(source != null ? source.getChannel() : null)
                            .sourceAgentId(source != null ? source.getAgentId() : null)
                            .sourceSession(source != null ? source.getSessionId() : null)
                            .expireAt(now + ORDER_EXPIRE_SECONDS)
                            .createdAt(now)
                            .updatedAt(now)
                            .build();

            order = orderRepository.save(order);
            orderItemRepository.saveAll(orderItems);
            order.setItems(orderItems);

            log.info("Order created: id={}, orderNo={}, total={}", orderId, orderNo, totalUnits);
            return order;

        } catch (Exception e) {
            // ============================================================
            // Compensation: Release locked inventory
            // ============================================================
            log.error(
                    "Failed to create order locally, releasing inventory lock: {}",
                    inventoryLock.getLockId(),
                    e);
            try {
                inventoryClient.releaseLock(
                        inventoryLock.getLockId(), "Order creation failed: " + e.getMessage());
            } catch (Exception releaseEx) {
                log.error(
                        "Failed to release inventory lock: {}",
                        inventoryLock.getLockId(),
                        releaseEx);
            }
            throw e;
        }
    }

    /** Get unit price: try inventory calendar price first, fallback to SKU base price. */
    private Money getUnitPrice(String skuId, Date date, StockKeepingUnit sku) {
        try {
            DailyInventory inv = inventoryClient.getDailyInventory(skuId, date);
            if (inv.hasPrice() && inv.getPrice().getUnits() > 0) {
                return inv.getPrice();
            }
        } catch (Exception e) {
            log.warn(
                    "Failed to get inventory price for sku={}, date={}, using base price",
                    skuId,
                    date,
                    e);
        }
        return sku.getBasePrice();
    }

    /** Get product name from SPU for snapshot. */
    private String getProductName(StockKeepingUnit sku) {
        if (!sku.getSpuId().isEmpty()) {
            try {
                return productClient.getSpuById(sku.getSpuId()).getName();
            } catch (Exception e) {
                log.warn("Failed to get SPU name for: {}", sku.getSpuId(), e);
            }
        }
        return sku.getName();
    }
}
