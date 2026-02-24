package org.tripsphere.inventory.service.impl;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tripsphere.inventory.exception.InsufficientInventoryException;
import org.tripsphere.inventory.exception.InvalidArgumentException;
import org.tripsphere.inventory.exception.NotFoundException;
import org.tripsphere.inventory.infra.redis.RedisInventoryClient;
import org.tripsphere.inventory.mapper.InventoryMapper;
import org.tripsphere.inventory.model.DailyInventoryEntity;
import org.tripsphere.inventory.model.InventoryLockEntity;
import org.tripsphere.inventory.model.InventoryLockItemEntity;
import org.tripsphere.inventory.repository.DailyInventoryRepository;
import org.tripsphere.inventory.repository.InventoryLockItemRepository;
import org.tripsphere.inventory.repository.InventoryLockRepository;
import org.tripsphere.inventory.service.InventoryService;
import org.tripsphere.inventory.v1.DailyInventory;
import org.tripsphere.inventory.v1.InventoryLock;
import org.tripsphere.inventory.v1.LockItem;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final DailyInventoryRepository dailyInventoryRepository;
    private final InventoryLockRepository inventoryLockRepository;
    private final InventoryLockItemRepository inventoryLockItemRepository;
    private final RedisInventoryClient redisClient;
    private final InventoryMapper inventoryMapper = InventoryMapper.INSTANCE;

    private static final int DEFAULT_LOCK_TIMEOUT = 900; // 15 minutes

    // ===================================================================
    // Daily Inventory Management
    // ===================================================================

    @Override
    @Transactional
    public DailyInventory setDailyInventory(
            String skuId,
            LocalDate date,
            int totalQuantity,
            String priceCurrency,
            long priceUnits,
            int priceNanos) {
        log.debug("Setting daily inventory: sku={}, date={}, total={}", skuId, date, totalQuantity);

        DailyInventoryEntity entity =
                dailyInventoryRepository.findBySkuIdAndInvDate(skuId, date).orElse(null);

        if (entity == null) {
            // Create new
            entity =
                    DailyInventoryEntity.builder()
                            .skuId(skuId)
                            .invDate(date)
                            .totalQty(totalQuantity)
                            .availableQty(totalQuantity)
                            .lockedQty(0)
                            .soldQty(0)
                            .priceCurrency(priceCurrency)
                            .priceUnits(priceUnits)
                            .priceNanos(priceNanos)
                            .updatedAt(Instant.now())
                            .build();
        } else {
            // Update: recalculate available = new_total - locked - sold
            int newAvailable = totalQuantity - entity.getLockedQty() - entity.getSoldQty();
            if (newAvailable < 0) {
                throw new InvalidArgumentException(
                        "Cannot set total_quantity to "
                                + totalQuantity
                                + ": would result in negative available quantity");
            }
            entity.setTotalQty(totalQuantity);
            entity.setAvailableQty(newAvailable);
            entity.setPriceCurrency(priceCurrency);
            entity.setPriceUnits(priceUnits);
            entity.setPriceNanos(priceNanos);
        }

        entity = dailyInventoryRepository.save(entity);

        // Sync to Redis cache
        redisClient.cacheInventory(entity);

        log.info(
                "Set daily inventory: sku={}, date={}, total={}, available={}",
                skuId,
                date,
                entity.getTotalQty(),
                entity.getAvailableQty());
        return inventoryMapper.toProto(entity);
    }

    @Override
    @Transactional
    public List<DailyInventory> batchSetDailyInventory(List<SetDailyInventoryParams> params) {
        log.debug("Batch setting {} daily inventory records", params.size());
        List<DailyInventory> results = new ArrayList<>();
        for (SetDailyInventoryParams p : params) {
            results.add(
                    setDailyInventory(
                            p.skuId(),
                            p.date(),
                            p.totalQuantity(),
                            p.priceCurrency(),
                            p.priceUnits(),
                            p.priceNanos()));
        }
        return results;
    }

    @Override
    public DailyInventory getDailyInventory(String skuId, LocalDate date) {
        log.debug("Getting daily inventory: sku={}, date={}", skuId, date);

        // Try Redis cache first
        Map<String, String> cached = redisClient.getCachedInventory(skuId, date);
        if (cached != null) {
            return buildDailyInventoryFromCache(skuId, date, cached);
        }

        // Fallback to MySQL
        DailyInventoryEntity entity =
                dailyInventoryRepository
                        .findBySkuIdAndInvDate(skuId, date)
                        .orElseThrow(
                                () -> new NotFoundException("DailyInventory", skuId + "/" + date));

        // Populate cache
        redisClient.cacheInventory(entity);
        return inventoryMapper.toProto(entity);
    }

    @Override
    public List<DailyInventory> queryInventoryCalendar(
            String skuId, LocalDate startDate, LocalDate endDate) {
        log.debug("Querying inventory calendar: sku={}, from={}, to={}", skuId, startDate, endDate);

        List<DailyInventoryEntity> entities =
                dailyInventoryRepository.findBySkuIdAndInvDateBetweenOrderByInvDateAsc(
                        skuId, startDate, endDate);

        // Populate cache for each entry
        entities.forEach(redisClient::cacheInventory);

        return inventoryMapper.toProtoList(entities);
    }

    @Override
    public CheckAvailabilityResult checkAvailability(String skuId, LocalDate date, int quantity) {
        log.debug("Checking availability: sku={}, date={}, qty={}", skuId, date, quantity);

        // Try cache first
        Map<String, String> cached = redisClient.getCachedInventory(skuId, date);
        if (cached != null) {
            int available = Integer.parseInt(cached.getOrDefault("available", "0"));
            return new CheckAvailabilityResult(
                    available >= quantity,
                    available,
                    cached.getOrDefault("price_ccy", "CNY"),
                    Long.parseLong(cached.getOrDefault("price_units", "0")),
                    Integer.parseInt(cached.getOrDefault("price_nanos", "0")));
        }

        // Fallback to MySQL
        DailyInventoryEntity entity =
                dailyInventoryRepository.findBySkuIdAndInvDate(skuId, date).orElse(null);

        if (entity == null) {
            return new CheckAvailabilityResult(false, 0, "CNY", 0, 0);
        }

        redisClient.cacheInventory(entity);
        return new CheckAvailabilityResult(
                entity.getAvailableQty() >= quantity,
                entity.getAvailableQty(),
                entity.getPriceCurrency(),
                entity.getPriceUnits(),
                entity.getPriceNanos());
    }

    // ===================================================================
    // Inventory Lock Operations
    // ===================================================================

    @Override
    @Transactional
    public InventoryLock lockInventory(
            List<LockItem> items, String orderId, int lockTimeoutSeconds) {
        log.debug("Locking inventory for order: {}, items: {}", orderId, items.size());

        if (lockTimeoutSeconds <= 0) {
            lockTimeoutSeconds = DEFAULT_LOCK_TIMEOUT;
        }

        // Prepare data for Redis Lua script
        List<String> skuIds = new ArrayList<>();
        List<LocalDate> dates = new ArrayList<>();
        List<Integer> quantities = new ArrayList<>();

        for (LockItem item : items) {
            String skuId = item.getSkuId();
            LocalDate date = inventoryMapper.protoToLocalDate(item.getDate());
            int qty = item.getQuantity();

            skuIds.add(skuId);
            dates.add(date);
            quantities.add(qty);

            // Ensure cache is populated
            Map<String, String> cached = redisClient.getCachedInventory(skuId, date);
            if (cached == null) {
                DailyInventoryEntity entity =
                        dailyInventoryRepository
                                .findBySkuIdAndInvDate(skuId, date)
                                .orElseThrow(
                                        () ->
                                                new NotFoundException(
                                                        "DailyInventory", skuId + "/" + date));
                redisClient.cacheInventory(entity);
            }
        }

        // Atomic lock via Redis Lua script
        boolean success = redisClient.lockInventoryAtomic(skuIds, dates, quantities);
        if (!success) {
            // Find which item failed for better error message
            for (int i = 0; i < skuIds.size(); i++) {
                Map<String, String> cached =
                        redisClient.getCachedInventory(skuIds.get(i), dates.get(i));
                int available =
                        cached != null
                                ? Integer.parseInt(cached.getOrDefault("available", "0"))
                                : 0;
                if (available < quantities.get(i)) {
                    throw new InsufficientInventoryException(
                            skuIds.get(i), dates.get(i).toString(), quantities.get(i), available);
                }
            }
            throw new InsufficientInventoryException(
                    skuIds.getFirst(), dates.getFirst().toString(), quantities.getFirst(), 0);
        }

        // Persist lock to MySQL
        long now = Instant.now().getEpochSecond();
        long expireAt = now + lockTimeoutSeconds;
        String lockId = UUID.randomUUID().toString();

        InventoryLockEntity lockEntity =
                InventoryLockEntity.builder()
                        .lockId(lockId)
                        .orderId(orderId)
                        .status("LOCKED")
                        .createdAt(now)
                        .expireAt(expireAt)
                        .build();
        lockEntity = inventoryLockRepository.save(lockEntity);

        // Save lock items
        List<InventoryLockItemEntity> lockItems = new ArrayList<>();
        for (int i = 0; i < skuIds.size(); i++) {
            InventoryLockItemEntity lockItem =
                    InventoryLockItemEntity.builder()
                            .lockId(lockId)
                            .skuId(skuIds.get(i))
                            .invDate(dates.get(i))
                            .quantity(quantities.get(i))
                            .build();
            lockItems.add(lockItem);
        }
        inventoryLockItemRepository.saveAll(lockItems);
        lockEntity.setItems(lockItems);

        // Update MySQL inventory
        for (int i = 0; i < skuIds.size(); i++) {
            updateMysqlInventoryForLock(skuIds.get(i), dates.get(i), quantities.get(i));
        }

        // Add to Redis lock expiry sorted set
        redisClient.addLockExpiry(lockId, expireAt);

        log.info("Inventory locked: lockId={}, orderId={}, expireAt={}", lockId, orderId, expireAt);
        return inventoryMapper.toLockProto(lockEntity);
    }

    @Override
    @Transactional
    public InventoryLock confirmLock(String lockId) {
        log.debug("Confirming lock: {}", lockId);

        InventoryLockEntity lockEntity =
                inventoryLockRepository
                        .findByLockId(lockId)
                        .orElseThrow(() -> new NotFoundException("InventoryLock", lockId));

        if (!"LOCKED".equals(lockEntity.getStatus())) {
            throw new InvalidArgumentException(
                    "Lock "
                            + lockId
                            + " is in status "
                            + lockEntity.getStatus()
                            + ", cannot confirm");
        }

        List<InventoryLockItemEntity> lockItems = inventoryLockItemRepository.findByLockId(lockId);

        // Prepare data for Redis
        List<String> skuIds = new ArrayList<>();
        List<LocalDate> dates = new ArrayList<>();
        List<Integer> quantities = new ArrayList<>();

        for (InventoryLockItemEntity item : lockItems) {
            skuIds.add(item.getSkuId());
            dates.add(item.getInvDate());
            quantities.add(item.getQuantity());
        }

        // Update Redis: locked -> sold
        redisClient.confirmLockAtomic(skuIds, dates, quantities);

        // Update MySQL: locked -> sold
        for (int i = 0; i < skuIds.size(); i++) {
            updateMysqlInventoryForConfirm(skuIds.get(i), dates.get(i), quantities.get(i));
        }

        // Update lock status
        lockEntity.setStatus("CONFIRMED");
        lockEntity = inventoryLockRepository.save(lockEntity);
        lockEntity.setItems(lockItems);

        // Remove from expiry set
        redisClient.removeLockExpiry(lockId);

        log.info("Lock confirmed: {}", lockId);
        return inventoryMapper.toLockProto(lockEntity);
    }

    @Override
    @Transactional
    public InventoryLock releaseLock(String lockId, String reason) {
        log.debug("Releasing lock: {}, reason: {}", lockId, reason);

        InventoryLockEntity lockEntity =
                inventoryLockRepository
                        .findByLockId(lockId)
                        .orElseThrow(() -> new NotFoundException("InventoryLock", lockId));

        if (!"LOCKED".equals(lockEntity.getStatus())) {
            throw new InvalidArgumentException(
                    "Lock "
                            + lockId
                            + " is in status "
                            + lockEntity.getStatus()
                            + ", cannot release");
        }

        List<InventoryLockItemEntity> lockItems = inventoryLockItemRepository.findByLockId(lockId);

        // Prepare data for Redis
        List<String> skuIds = new ArrayList<>();
        List<LocalDate> dates = new ArrayList<>();
        List<Integer> quantities = new ArrayList<>();

        for (InventoryLockItemEntity item : lockItems) {
            skuIds.add(item.getSkuId());
            dates.add(item.getInvDate());
            quantities.add(item.getQuantity());
        }

        // Update Redis: locked -> available
        redisClient.releaseLockAtomic(skuIds, dates, quantities);

        // Update MySQL: locked -> available
        for (int i = 0; i < skuIds.size(); i++) {
            updateMysqlInventoryForRelease(skuIds.get(i), dates.get(i), quantities.get(i));
        }

        // Update lock status
        lockEntity.setStatus("RELEASED");
        lockEntity = inventoryLockRepository.save(lockEntity);
        lockEntity.setItems(lockItems);

        // Remove from expiry set
        redisClient.removeLockExpiry(lockId);

        log.info("Lock released: {}, reason: {}", lockId, reason);
        return inventoryMapper.toLockProto(lockEntity);
    }

    // ===================================================================
    // MySQL inventory update helpers
    // ===================================================================

    private void updateMysqlInventoryForLock(String skuId, LocalDate date, int qty) {
        dailyInventoryRepository
                .findBySkuIdAndInvDate(skuId, date)
                .ifPresent(
                        entity -> {
                            entity.setAvailableQty(entity.getAvailableQty() - qty);
                            entity.setLockedQty(entity.getLockedQty() + qty);
                            dailyInventoryRepository.save(entity);
                        });
    }

    private void updateMysqlInventoryForConfirm(String skuId, LocalDate date, int qty) {
        dailyInventoryRepository
                .findBySkuIdAndInvDate(skuId, date)
                .ifPresent(
                        entity -> {
                            entity.setLockedQty(entity.getLockedQty() - qty);
                            entity.setSoldQty(entity.getSoldQty() + qty);
                            dailyInventoryRepository.save(entity);
                        });
    }

    private void updateMysqlInventoryForRelease(String skuId, LocalDate date, int qty) {
        dailyInventoryRepository
                .findBySkuIdAndInvDate(skuId, date)
                .ifPresent(
                        entity -> {
                            entity.setLockedQty(entity.getLockedQty() - qty);
                            entity.setAvailableQty(entity.getAvailableQty() + qty);
                            dailyInventoryRepository.save(entity);
                        });
    }

    // ===================================================================
    // Build DailyInventory from Redis cache
    // ===================================================================

    private DailyInventory buildDailyInventoryFromCache(
            String skuId, LocalDate date, Map<String, String> cached) {
        return DailyInventory.newBuilder()
                .setSkuId(skuId)
                .setDate(inventoryMapper.localDateToProto(date))
                .setTotalQuantity(Integer.parseInt(cached.getOrDefault("total", "0")))
                .setAvailableQuantity(Integer.parseInt(cached.getOrDefault("available", "0")))
                .setLockedQuantity(Integer.parseInt(cached.getOrDefault("locked", "0")))
                .setSoldQuantity(Integer.parseInt(cached.getOrDefault("sold", "0")))
                .setPrice(
                        org.tripsphere.common.v1.Money.newBuilder()
                                .setCurrency(cached.getOrDefault("price_ccy", "CNY"))
                                .setUnits(Long.parseLong(cached.getOrDefault("price_units", "0")))
                                .setNanos(Integer.parseInt(cached.getOrDefault("price_nanos", "0")))
                                .build())
                .build();
    }
}
