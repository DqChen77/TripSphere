package org.tripsphere.order.application.service.command;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.tripsphere.order.application.dto.CreateOrderCommand;
import org.tripsphere.order.application.dto.CreateOrderItemCommand;
import org.tripsphere.order.application.dto.DailyInventoryInfo;
import org.tripsphere.order.application.dto.LockItemData;
import org.tripsphere.order.application.dto.SkuInfo;
import org.tripsphere.order.application.dto.SpuInfo;
import org.tripsphere.order.application.exception.InvalidArgumentException;
import org.tripsphere.order.application.port.InventoryPort;
import org.tripsphere.order.application.port.OrderCachePort;
import org.tripsphere.order.application.port.ProductPort;
import org.tripsphere.order.domain.model.Money;
import org.tripsphere.order.domain.model.Order;
import org.tripsphere.order.domain.model.OrderItem;
import org.tripsphere.order.domain.model.OrderType;
import org.tripsphere.order.domain.repository.OrderRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreateOrderUseCase {

    private final OrderRepository orderRepository;
    private final ProductPort productPort;
    private final InventoryPort inventoryPort;
    private final OrderCachePort cachePort;
    private final PlatformTransactionManager transactionManager;

    private static final int ORDER_EXPIRE_SECONDS = 900;

    private record OrderContext(String resourceId, OrderType orderType) {}

    public Order execute(CreateOrderCommand command) {
        log.info(
                "Creating order for user: {}, items: {}",
                command.userId(),
                command.items().size());

        checkDuplicateSubmission(command);
        String orderNo = generateOrderNo();

        // Step 1: Validate SKUs
        List<String> skuIds = command.items().stream()
                .map(CreateOrderItemCommand::skuId)
                .distinct()
                .toList();
        List<SkuInfo> skus = productPort.batchGetSkus(skuIds);
        Map<String, SkuInfo> skuMap = skus.stream().collect(Collectors.toMap(SkuInfo::id, s -> s));

        for (String skuId : skuIds) {
            SkuInfo sku = skuMap.get(skuId);
            if (sku == null) throw new InvalidArgumentException("SKU not found: " + skuId);
            if (!sku.active()) throw new InvalidArgumentException("SKU is not active: " + skuId);
        }

        // Step 2: Fetch SPUs
        List<String> spuIds = skus.stream()
                .map(SkuInfo::spuId)
                .filter(id -> !id.isEmpty())
                .distinct()
                .toList();
        List<SpuInfo> spus = productPort.batchGetSpus(spuIds);
        Map<String, SpuInfo> spuMap = spus.stream().collect(Collectors.toMap(SpuInfo::id, s -> s));

        // Step 3: Validate homogeneity
        OrderContext ctx = validateAndGetContext(command.items(), skuMap, spuMap);

        // Step 4: Lock inventory
        String orderId = UUID.randomUUID().toString();
        List<LockItemData> lockItems = buildLockItems(command.items());

        String lockId;
        try {
            lockId = inventoryPort.lockInventory(lockItems, orderId, ORDER_EXPIRE_SECONDS);
        } catch (Exception e) {
            log.error("Failed to lock inventory for order: {}", orderId, e);
            throw e;
        }

        // Step 5: Fetch prices
        Map<String, Money> priceCache = fetchPrices(command.items(), skuMap);

        // Step 6: Persist order (transactional), compensate on failure
        try {
            Order order = persistOrder(orderId, orderNo, command, skuMap, spuMap, priceCache, lockId, ctx);

            if (order.getExpireAt() != null) {
                cachePort.addOrderExpiry(order.getId(), order.getExpireAt());
            }
            return order;
        } catch (Exception e) {
            log.error("Failed to persist order, releasing inventory lock: {}", lockId, e);
            try {
                inventoryPort.releaseLock(lockId, "Order creation failed: " + e.getMessage());
            } catch (Exception releaseEx) {
                log.error("Failed to release inventory lock: {}", lockId, releaseEx);
            }
            throw e;
        }
    }

    // ---- Validation ----

    private OrderContext validateAndGetContext(
            List<CreateOrderItemCommand> items, Map<String, SkuInfo> skuMap, Map<String, SpuInfo> spuMap) {

        Set<String> resourceTypes = items.stream()
                .map(item -> requireSpu(skuMap, spuMap, item).resourceType())
                .collect(Collectors.toSet());

        if (resourceTypes.size() > 1) {
            throw new InvalidArgumentException("All items must have the same resource type, found: " + resourceTypes);
        }

        String resourceType = resourceTypes.iterator().next();
        if ("UNSPECIFIED".equals(resourceType) || resourceType == null) {
            throw new InvalidArgumentException("Order items have unspecified resource type");
        }

        Set<String> topLevelIds = new HashSet<>();
        for (CreateOrderItemCommand item : items) {
            SpuInfo spu = requireSpu(skuMap, spuMap, item);
            topLevelIds.add(extractTopLevelResourceId(spu, resourceType));
        }

        if (topLevelIds.size() > 1) {
            String entityLabel = "HOTEL_ROOM".equals(resourceType) ? "hotel" : "attraction";
            throw new InvalidArgumentException("All items must belong to the same " + entityLabel);
        }

        validateDateConsistency(items, resourceType);

        return new OrderContext(topLevelIds.iterator().next(), resourceTypeToOrderType(resourceType));
    }

    @SuppressWarnings("unchecked")
    private String extractTopLevelResourceId(SpuInfo spu, String resourceType) {
        return switch (resourceType) {
            case "HOTEL_ROOM" -> {
                Object hotelIdObj = spu.attributes() != null ? spu.attributes().get("hotel_id") : null;
                String hotelId = hotelIdObj != null ? hotelIdObj.toString() : "";
                if (hotelId.isEmpty()) {
                    throw new InvalidArgumentException("SPU " + spu.id() + " is missing hotel_id in attributes");
                }
                yield hotelId;
            }
            case "ATTRACTION" -> spu.resourceId();
            default -> throw new InvalidArgumentException("Unsupported resource type: " + resourceType);
        };
    }

    private void validateDateConsistency(List<CreateOrderItemCommand> items, String resourceType) {
        Set<LocalDate> startDates =
                items.stream().map(CreateOrderItemCommand::date).collect(Collectors.toSet());

        if (startDates.size() > 1) {
            throw new InvalidArgumentException("All items must share the same date");
        }

        if ("HOTEL_ROOM".equals(resourceType)) {
            for (CreateOrderItemCommand item : items) {
                if (item.endDate() == null) {
                    throw new InvalidArgumentException("Hotel room items must have an end_date (check-out date)");
                }
            }
            Set<LocalDate> endDates =
                    items.stream().map(CreateOrderItemCommand::endDate).collect(Collectors.toSet());
            if (endDates.size() > 1) {
                throw new InvalidArgumentException("All hotel room items must share the same check-out date");
            }
            LocalDate checkIn = items.get(0).date();
            LocalDate checkOut = items.get(0).endDate();
            if (!checkOut.isAfter(checkIn)) {
                throw new InvalidArgumentException("Check-out date must be after check-in date");
            }
        } else {
            for (CreateOrderItemCommand item : items) {
                if (item.endDate() != null) {
                    throw new InvalidArgumentException("Attraction ticket items must not have an end_date");
                }
            }
        }
    }

    // ---- Data fetching ----

    private List<LockItemData> buildLockItems(List<CreateOrderItemCommand> items) {
        List<LockItemData> lockItems = new ArrayList<>();
        for (CreateOrderItemCommand item : items) {
            if (item.endDate() != null && item.endDate().isAfter(item.date())) {
                for (LocalDate d = item.date(); d.isBefore(item.endDate()); d = d.plusDays(1)) {
                    lockItems.add(new LockItemData(item.skuId(), d, item.quantity()));
                }
            } else {
                lockItems.add(new LockItemData(item.skuId(), item.date(), item.quantity()));
            }
        }
        return lockItems;
    }

    private Map<String, Money> fetchPrices(List<CreateOrderItemCommand> items, Map<String, SkuInfo> skuMap) {
        Map<String, List<LocalDate>> skuDatesMap = new LinkedHashMap<>();
        for (CreateOrderItemCommand item : items) {
            List<LocalDate> dates = skuDatesMap.computeIfAbsent(item.skuId(), k -> new ArrayList<>());
            if (item.endDate() != null && item.endDate().isAfter(item.date())) {
                for (LocalDate d = item.date(); d.isBefore(item.endDate()); d = d.plusDays(1)) {
                    dates.add(d);
                }
            } else {
                dates.add(item.date());
            }
        }

        Map<String, Money> priceCache = new HashMap<>();
        for (Map.Entry<String, List<LocalDate>> entry : skuDatesMap.entrySet()) {
            String skuId = entry.getKey();
            List<LocalDate> dates = entry.getValue();
            LocalDate minDate = dates.stream().min(LocalDate::compareTo).orElse(null);
            LocalDate maxDate = dates.stream().max(LocalDate::compareTo).orElse(null);
            if (minDate == null) continue;

            try {
                List<DailyInventoryInfo> inventories = inventoryPort.queryInventoryCalendar(skuId, minDate, maxDate);
                for (DailyInventoryInfo inv : inventories) {
                    if (inv.price() != null && inv.price().units() > 0) {
                        priceCache.put(skuId + ":" + inv.date(), inv.price());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to fetch prices for sku={}, falling back to base price", skuId, e);
            }
        }
        return priceCache;
    }

    // ---- Persistence ----

    private Order persistOrder(
            String orderId,
            String orderNo,
            CreateOrderCommand command,
            Map<String, SkuInfo> skuMap,
            Map<String, SpuInfo> spuMap,
            Map<String, Money> priceCache,
            String lockId,
            OrderContext ctx) {

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        return txTemplate.execute(status -> {
            List<OrderItem> orderItems = new ArrayList<>();
            long totalUnits = 0;
            int totalNanos = 0;
            String totalCurrency = "CNY";

            for (CreateOrderItemCommand createItem : command.items()) {
                SkuInfo sku = skuMap.get(createItem.skuId());
                SpuInfo spu = spuMap.get(sku.spuId());

                long itemSubtotalUnits = 0;
                int itemSubtotalNanos = 0;
                Money firstDayPrice = null;

                if (createItem.endDate() != null && createItem.endDate().isAfter(createItem.date())) {
                    for (LocalDate d = createItem.date(); d.isBefore(createItem.endDate()); d = d.plusDays(1)) {
                        Money dayPrice = lookupPrice(sku, d, priceCache);
                        if (firstDayPrice == null) firstDayPrice = dayPrice;
                        itemSubtotalUnits += dayPrice.units() * createItem.quantity();
                        itemSubtotalNanos += dayPrice.nanos() * createItem.quantity();
                    }
                } else {
                    Money unitPrice = lookupPrice(sku, createItem.date(), priceCache);
                    firstDayPrice = unitPrice;
                    itemSubtotalUnits = unitPrice.units() * createItem.quantity();
                    itemSubtotalNanos = unitPrice.nanos() * createItem.quantity();
                }

                itemSubtotalUnits += itemSubtotalNanos / 1_000_000_000;
                itemSubtotalNanos = itemSubtotalNanos % 1_000_000_000;

                if (firstDayPrice == null) firstDayPrice = sku.basePrice();
                totalCurrency = firstDayPrice.currency().isEmpty() ? "CNY" : firstDayPrice.currency();

                OrderItem orderItem = OrderItem.builder()
                        .id(UUID.randomUUID().toString())
                        .orderId(orderId)
                        .spuId(sku.spuId())
                        .skuId(sku.id())
                        .productName(spu != null ? spu.name() : sku.name())
                        .skuName(sku.name())
                        .resourceType(spu != null ? spu.resourceType() : null)
                        .resourceId(ctx.resourceId())
                        .spuImage(extractFirstImage(spu))
                        .spuDescription(spu != null ? spu.description() : null)
                        .skuAttributes(sku.attributes())
                        .itemDate(createItem.date())
                        .endDate(createItem.endDate())
                        .quantity(createItem.quantity())
                        .unitPrice(new Money(totalCurrency, firstDayPrice.units(), firstDayPrice.nanos()))
                        .subtotal(new Money(totalCurrency, itemSubtotalUnits, itemSubtotalNanos))
                        .invLockId(lockId)
                        .build();
                orderItems.add(orderItem);

                totalUnits += itemSubtotalUnits;
                totalNanos += itemSubtotalNanos;
            }

            totalUnits += totalNanos / 1_000_000_000;
            totalNanos = totalNanos % 1_000_000_000;

            long now = java.time.Instant.now().getEpochSecond();
            Order order = Order.create(
                    orderId,
                    orderNo,
                    command.userId(),
                    ctx.orderType(),
                    ctx.resourceId(),
                    new Money(totalCurrency, totalUnits, totalNanos),
                    command.contact(),
                    command.source(),
                    now + ORDER_EXPIRE_SECONDS,
                    orderItems);

            order = orderRepository.save(order);
            log.info(
                    "Order created: id={}, orderNo={}, type={}, resourceId={}",
                    orderId,
                    orderNo,
                    ctx.orderType(),
                    ctx.resourceId());
            return order;
        });
    }

    // ---- Helpers ----

    private Money lookupPrice(SkuInfo sku, LocalDate date, Map<String, Money> priceCache) {
        Money cached = priceCache.get(sku.id() + ":" + date);
        return cached != null ? cached : sku.basePrice();
    }

    private SpuInfo requireSpu(Map<String, SkuInfo> skuMap, Map<String, SpuInfo> spuMap, CreateOrderItemCommand item) {
        SkuInfo sku = skuMap.get(item.skuId());
        SpuInfo spu = spuMap.get(sku.spuId());
        if (spu == null) {
            throw new InvalidArgumentException("SPU not found for SKU: " + item.skuId());
        }
        return spu;
    }

    private OrderType resourceTypeToOrderType(String resourceType) {
        return switch (resourceType) {
            case "HOTEL_ROOM" -> OrderType.HOTEL;
            case "ATTRACTION" -> OrderType.ATTRACTION;
            default -> OrderType.UNSPECIFIED;
        };
    }

    private String extractFirstImage(SpuInfo spu) {
        if (spu == null || spu.images() == null || spu.images().isEmpty()) return null;
        String image = spu.images().get(0);
        return image.isEmpty() ? null : image;
    }

    private void checkDuplicateSubmission(CreateOrderCommand command) {
        String fingerprint = command.items().stream()
                .map(i -> i.skuId() + ":" + i.date() + ":" + i.quantity())
                .sorted()
                .collect(Collectors.joining("|"));

        if (!cachePort.tryAcquireDedup(command.userId(), fingerprint)) {
            throw new InvalidArgumentException(
                    "Duplicate order submission detected. Please wait a moment before retrying.");
        }
    }

    private String generateOrderNo() {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long seq = orderRepository.getNextOrderSequence();
        return String.format("TS%s%06d", today, seq);
    }
}
