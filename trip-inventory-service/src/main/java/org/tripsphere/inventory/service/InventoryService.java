package org.tripsphere.inventory.service;

import java.time.LocalDate;
import java.util.List;
import org.tripsphere.inventory.v1.DailyInventory;
import org.tripsphere.inventory.v1.InventoryLock;
import org.tripsphere.inventory.v1.LockItem;

public interface InventoryService {

    /** Set or update daily inventory for a SKU on a specific date (upsert). */
    DailyInventory setDailyInventory(
            String skuId,
            LocalDate date,
            int totalQuantity,
            String priceCurrency,
            long priceUnits,
            int priceNanos);

    /** Batch set daily inventory. */
    List<DailyInventory> batchSetDailyInventory(List<SetDailyInventoryParams> params);

    /** Get daily inventory for a single SKU + date. */
    DailyInventory getDailyInventory(String skuId, LocalDate date);

    /** Query inventory calendar for a SKU over a date range. */
    List<DailyInventory> queryInventoryCalendar(
            String skuId, LocalDate startDate, LocalDate endDate);

    /** Check availability for a specific SKU + date + quantity. */
    CheckAvailabilityResult checkAvailability(String skuId, LocalDate date, int quantity);

    /** Atomically lock inventory for one or more items. */
    InventoryLock lockInventory(List<LockItem> items, String orderId, int lockTimeoutSeconds);

    /** Confirm a lock after payment succeeds. */
    InventoryLock confirmLock(String lockId);

    /** Release a lock on cancellation or timeout. */
    InventoryLock releaseLock(String lockId, String reason);

    // ===================================================================
    // Helper records
    // ===================================================================

    record SetDailyInventoryParams(
            String skuId,
            LocalDate date,
            int totalQuantity,
            String priceCurrency,
            long priceUnits,
            int priceNanos) {}

    record CheckAvailabilityResult(
            boolean available,
            int availableQuantity,
            String priceCurrency,
            long priceUnits,
            int priceNanos) {}
}
