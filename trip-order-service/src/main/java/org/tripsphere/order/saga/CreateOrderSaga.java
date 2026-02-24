package org.tripsphere.order.saga;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import org.tripsphere.product.v1.StandardProductUnit;
import org.tripsphere.product.v1.StockKeepingUnit;

/**
 * Saga Orchestrator for CreateOrder. Coordinates: Product validation → Inventory locking → Order
 * persistence. Includes compensation (inventory release) on failure.
 *
 * <p>The persist step uses {@link TransactionTemplate} so that the DB transaction only covers the
 * local write — gRPC calls to Product and Inventory services run outside any DB transaction,
 * avoiding connection-pool exhaustion under load.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreateOrderSaga {

    private final ProductServiceClient productClient;
    private final InventoryServiceClient inventoryClient;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PlatformTransactionManager transactionManager;
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
        // Step 1: Validate SKUs via Product Service (batch)
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
        // Step 1.5: Batch fetch SPU names for product snapshots
        // ============================================================
        Map<String, String> spuNameMap = fetchSpuNames(skus);

        // ============================================================
        // Step 2: Lock Inventory (with hotel date expansion)
        // ============================================================
        String orderId = UUID.randomUUID().toString();
        List<LockItem> lockItems = buildLockItems(items);

        InventoryLock inventoryLock;
        try {
            inventoryLock = inventoryClient.lockInventory(lockItems, orderId, ORDER_EXPIRE_SECONDS);
        } catch (Exception e) {
            log.error("Failed to lock inventory for order: {}", orderId, e);
            throw e;
        }

        // ============================================================
        // Step 2.5: Batch fetch prices for all (sku, date) pairs
        // ============================================================
        Map<String, Money> priceCache = fetchPrices(items, skuMap);

        // ============================================================
        // Step 3: Create Order locally (in its own DB transaction)
        // ============================================================
        try {
            return persistOrder(
                    orderId,
                    orderNo,
                    userId,
                    items,
                    contact,
                    source,
                    skuMap,
                    spuNameMap,
                    priceCache,
                    inventoryLock);
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

    // ===================================================================
    // Helper: Batch fetch SPU names
    // ===================================================================

    /**
     * Batch fetch SPU names for product snapshots using a single batchGetSpus call instead of N
     * individual getSpuById calls.
     */
    private Map<String, String> fetchSpuNames(List<StockKeepingUnit> skus) {
        List<String> spuIds =
                skus.stream()
                        .map(StockKeepingUnit::getSpuId)
                        .filter(id -> !id.isEmpty())
                        .distinct()
                        .toList();

        Map<String, String> spuNameMap = new HashMap<>();
        if (!spuIds.isEmpty()) {
            try {
                List<StandardProductUnit> spus = productClient.batchGetSpus(spuIds);
                for (StandardProductUnit spu : spus) {
                    spuNameMap.put(spu.getId(), spu.getName());
                }
            } catch (Exception e) {
                log.warn("Failed to batch fetch SPU names, will use SKU names as fallback", e);
            }
        }
        return spuNameMap;
    }

    // ===================================================================
    // Helper: Build lock items with hotel date expansion
    // ===================================================================

    /**
     * Build lock items from order items. For hotel bookings (where end_date is present), expands
     * the date range into individual daily lock items — e.g. check-in Feb 25, check-out Feb 28
     * produces lock items for Feb 25, Feb 26, Feb 27 (3 nights).
     */
    private List<LockItem> buildLockItems(List<CreateOrderItem> items) {
        List<LockItem> lockItems = new ArrayList<>();
        for (CreateOrderItem item : items) {
            LocalDate startDate = orderMapper.protoToLocalDate(item.getDate());
            LocalDate endDate =
                    item.hasEndDate() ? orderMapper.protoToLocalDate(item.getEndDate()) : null;

            if (endDate != null && endDate.isAfter(startDate)) {
                // Hotel: lock each night from check-in to check-out (exclusive)
                for (LocalDate d = startDate; d.isBefore(endDate); d = d.plusDays(1)) {
                    lockItems.add(
                            LockItem.newBuilder()
                                    .setSkuId(item.getSkuId())
                                    .setDate(orderMapper.localDateToProto(d))
                                    .setQuantity(item.getQuantity())
                                    .build());
                }
            } else {
                // Attraction or single-date item
                lockItems.add(
                        LockItem.newBuilder()
                                .setSkuId(item.getSkuId())
                                .setDate(item.getDate())
                                .setQuantity(item.getQuantity())
                                .build());
            }
        }
        return lockItems;
    }

    // ===================================================================
    // Helper: Batch fetch prices
    // ===================================================================

    /**
     * Batch fetch prices for all (sku, date) combinations needed, using QueryInventoryCalendar per
     * distinct SKU instead of N individual GetDailyInventory calls. Returns a map keyed by
     * "skuId:date" → Money.
     */
    private Map<String, Money> fetchPrices(
            List<CreateOrderItem> items, Map<String, StockKeepingUnit> skuMap) {

        // Collect all (skuId → dates) needed (including expanded hotel dates)
        Map<String, List<LocalDate>> skuDatesMap = new LinkedHashMap<>();
        for (CreateOrderItem item : items) {
            LocalDate startDate = orderMapper.protoToLocalDate(item.getDate());
            LocalDate endDate =
                    item.hasEndDate() ? orderMapper.protoToLocalDate(item.getEndDate()) : null;

            List<LocalDate> dates =
                    skuDatesMap.computeIfAbsent(item.getSkuId(), k -> new ArrayList<>());

            if (endDate != null && endDate.isAfter(startDate)) {
                for (LocalDate d = startDate; d.isBefore(endDate); d = d.plusDays(1)) {
                    dates.add(d);
                }
            } else {
                dates.add(startDate);
            }
        }

        // Fetch prices per SKU using calendar query (1 gRPC call per distinct SKU)
        Map<String, Money> priceCache = new HashMap<>();
        for (Map.Entry<String, List<LocalDate>> entry : skuDatesMap.entrySet()) {
            String skuId = entry.getKey();
            List<LocalDate> dates = entry.getValue();
            LocalDate minDate = dates.stream().min(LocalDate::compareTo).orElse(null);
            LocalDate maxDate = dates.stream().max(LocalDate::compareTo).orElse(null);

            if (minDate == null) continue;

            try {
                List<DailyInventory> inventories =
                        inventoryClient.queryInventoryCalendar(
                                skuId,
                                orderMapper.localDateToProto(minDate),
                                orderMapper.localDateToProto(maxDate));
                for (DailyInventory inv : inventories) {
                    if (inv.hasPrice() && inv.getPrice().getUnits() > 0) {
                        LocalDate invDate = orderMapper.protoToLocalDate(inv.getDate());
                        priceCache.put(skuId + ":" + invDate, inv.getPrice());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to fetch inventory prices for sku={}, using base price", skuId, e);
            }
        }

        return priceCache;
    }

    // ===================================================================
    // Helper: Persist order in a dedicated DB transaction
    // ===================================================================

    /**
     * Persist order and items within a narrow DB transaction (via TransactionTemplate). This
     * ensures the DB connection is NOT held during the preceding gRPC calls.
     */
    private OrderEntity persistOrder(
            String orderId,
            String orderNo,
            String userId,
            List<CreateOrderItem> items,
            ContactInfo contact,
            OrderSource source,
            Map<String, StockKeepingUnit> skuMap,
            Map<String, String> spuNameMap,
            Map<String, Money> priceCache,
            InventoryLock inventoryLock) {

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        return txTemplate.execute(
                status -> {
                    long now = Instant.now().getEpochSecond();

                    List<OrderItemEntity> orderItems = new ArrayList<>();
                    long totalUnits = 0;
                    int totalNanos = 0;
                    String totalCurrency = "CNY";

                    for (CreateOrderItem createItem : items) {
                        StockKeepingUnit sku = skuMap.get(createItem.getSkuId());
                        String itemId = UUID.randomUUID().toString();

                        // Calculate price — supports hotel multi-night ranges
                        LocalDate startDate = orderMapper.protoToLocalDate(createItem.getDate());
                        LocalDate endDate =
                                createItem.hasEndDate()
                                        ? orderMapper.protoToLocalDate(createItem.getEndDate())
                                        : null;

                        long itemSubtotalUnits = 0;
                        int itemSubtotalNanos = 0;
                        Money firstDayPrice = null;

                        if (endDate != null && endDate.isAfter(startDate)) {
                            // Hotel: sum prices for each night
                            for (LocalDate d = startDate; d.isBefore(endDate); d = d.plusDays(1)) {
                                Money dayPrice =
                                        lookupPrice(createItem.getSkuId(), d, priceCache, sku);
                                if (firstDayPrice == null) firstDayPrice = dayPrice;
                                itemSubtotalUnits += dayPrice.getUnits() * createItem.getQuantity();
                                itemSubtotalNanos += dayPrice.getNanos() * createItem.getQuantity();
                            }
                        } else {
                            // Attraction or single-date
                            Money unitPrice =
                                    lookupPrice(createItem.getSkuId(), startDate, priceCache, sku);
                            firstDayPrice = unitPrice;
                            itemSubtotalUnits = unitPrice.getUnits() * createItem.getQuantity();
                            itemSubtotalNanos = unitPrice.getNanos() * createItem.getQuantity();
                        }

                        // Handle nanos overflow
                        itemSubtotalUnits += itemSubtotalNanos / 1_000_000_000;
                        itemSubtotalNanos = itemSubtotalNanos % 1_000_000_000;

                        if (firstDayPrice == null) {
                            firstDayPrice = sku.getBasePrice();
                        }
                        totalCurrency =
                                firstDayPrice.getCurrency().isEmpty()
                                        ? "CNY"
                                        : firstDayPrice.getCurrency();

                        // Product name from pre-fetched SPU map
                        String productName = spuNameMap.getOrDefault(sku.getSpuId(), sku.getName());

                        OrderItemEntity orderItem =
                                OrderItemEntity.builder()
                                        .id(itemId)
                                        .orderId(orderId)
                                        .spuId(sku.getSpuId())
                                        .skuId(sku.getId())
                                        .productName(productName)
                                        .skuName(sku.getName())
                                        .itemDate(startDate)
                                        .endDate(endDate)
                                        .quantity(createItem.getQuantity())
                                        .unitPriceCcy(totalCurrency)
                                        .unitPriceUnits(firstDayPrice.getUnits())
                                        .unitPriceNanos(firstDayPrice.getNanos())
                                        .subtotalCcy(totalCurrency)
                                        .subtotalUnits(itemSubtotalUnits)
                                        .subtotalNanos(itemSubtotalNanos)
                                        .invLockId(inventoryLock.getLockId())
                                        .build();
                        orderItems.add(orderItem);

                        totalUnits += itemSubtotalUnits;
                        totalNanos += itemSubtotalNanos;
                    }

                    // Handle total nanos overflow
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

                    log.info(
                            "Order created: id={}, orderNo={}, total={}",
                            orderId,
                            orderNo,
                            totalUnits);
                    return order;
                });
    }

    /**
     * Look up price for a specific SKU on a specific date from the pre-fetched cache. Falls back to
     * the SKU's base_price if no calendar price is available.
     */
    private Money lookupPrice(
            String skuId, LocalDate date, Map<String, Money> priceCache, StockKeepingUnit sku) {
        Money cached = priceCache.get(skuId + ":" + date);
        if (cached != null) {
            return cached;
        }
        return sku.getBasePrice();
    }
}
