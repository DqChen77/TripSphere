package org.tripsphere.order.grpc.client;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;
import org.tripsphere.inventory.v1.*;

/**
 * gRPC client for trip-inventory-service. Used by Order Service for locking, confirming, and
 * releasing inventory.
 */
@Slf4j
@Component
public class InventoryServiceClient {

    @GrpcClient("trip-inventory-service")
    private InventoryServiceGrpc.InventoryServiceBlockingStub inventoryStub;

    /** Lock inventory for a set of items. */
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

    /** Confirm a lock after payment. */
    public InventoryLock confirmLock(String lockId) {
        log.debug("Confirming inventory lock: {}", lockId);
        ConfirmLockResponse response =
                inventoryStub.confirmLock(
                        ConfirmLockRequest.newBuilder().setLockId(lockId).build());
        return response.getLock();
    }

    /** Release a lock (on cancel or timeout). */
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

    /** Get daily inventory (for price lookup). */
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
}
