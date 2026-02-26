package org.tripsphere.order.grpc.client;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;
import org.tripsphere.inventory.v1.*;

@Slf4j
@Component
public class InventoryServiceClient {

    @GrpcClient("trip-inventory-service")
    private InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub;

    public InventoryLock lockInventory(
            List<LockItem> items, String orderId, int lockTimeoutSeconds) {
        log.debug("Locking inventory for order: {}, items: {}", orderId, items.size());
        LockInventoryResponse response =
                inventoryStub.lockInventory(
                        LockInventoryRequest.newBuilder()
                                .addAllItems(items)
                                .setOrderId(orderId)
                                .setLockTimeoutSeconds(lockTimeoutSeconds)
                                .build());
        return response.getLock();
    }

    public InventoryLock confirmLock(String lockId) {
        log.debug("Confirming inventory lock: {}", lockId);
        ConfirmLockResponse response =
                inventoryStub.confirmLock(
                        ConfirmLockRequest.newBuilder().setLockId(lockId).build());
        return response.getLock();
    }

    public InventoryLock releaseLock(String lockId, String reason) {
        log.debug("Releasing inventory lock: {}, reason: {}", lockId, reason);
        ReleaseLockResponse response =
                inventoryStub.releaseLock(
                        ReleaseLockRequest.newBuilder()
                                .setLockId(lockId)
                                .setReason(reason)
                                .build());
        return response.getLock();
    }

    public DailyInventory getDailyInventory(String skuId, org.tripsphere.common.v1.Date date) {
        log.debug("Getting daily inventory: sku={}, date={}", skuId, date);
        GetDailyInventoryResponse response =
                inventoryStub.getDailyInventory(
                        GetDailyInventoryRequest.newBuilder()
                                .setSkuId(skuId)
                                .setDate(date)
                                .build());
        return response.getInventory();
    }

    public List<DailyInventory> queryInventoryCalendar(
            String skuId,
            org.tripsphere.common.v1.Date startDate,
            org.tripsphere.common.v1.Date endDate) {
        log.debug("Querying inventory calendar: sku={}, from={}, to={}", skuId, startDate, endDate);
        QueryInventoryCalendarResponse response =
                inventoryStub.queryInventoryCalendar(
                        QueryInventoryCalendarRequest.newBuilder()
                                .setSkuId(skuId)
                                .setStartDate(startDate)
                                .setEndDate(endDate)
                                .build());
        return response.getEntriesList();
    }
}
