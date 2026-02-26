package org.tripsphere.inventory.service;

import java.time.LocalDate;
import java.util.List;
import org.tripsphere.inventory.v1.DailyInventory;
import org.tripsphere.inventory.v1.InventoryLock;
import org.tripsphere.inventory.v1.LockItem;

public interface InventoryService {

    DailyInventory setDailyInventory(
            String skuId,
            LocalDate date,
            int totalQuantity,
            String priceCurrency,
            long priceUnits,
            int priceNanos);

    List<DailyInventory> batchSetDailyInventory(List<SetDailyInventoryParams> params);

    DailyInventory getDailyInventory(String skuId, LocalDate date);

    List<DailyInventory> queryInventoryCalendar(
            String skuId, LocalDate startDate, LocalDate endDate);

    CheckAvailabilityResult checkAvailability(String skuId, LocalDate date, int quantity);

    InventoryLock lockInventory(List<LockItem> items, String orderId, int lockTimeoutSeconds);

    InventoryLock confirmLock(String lockId);

    InventoryLock releaseLock(String lockId, String reason);

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
