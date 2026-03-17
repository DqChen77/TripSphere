package org.tripsphere.inventory.domain.model;

import com.github.f4b6a3.uuid.UuidCreator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class InventoryLock {

    private String lockId;
    private String orderId;
    private LockStatus status;
    private long createdAt;
    private long expireAt;

    @Builder.Default
    private List<InventoryLockItem> items = new ArrayList<>();

    public static InventoryLock create(String orderId, int lockTimeoutSeconds) {
        long now = Instant.now().getEpochSecond();
        return InventoryLock.builder()
                .lockId(UuidCreator.getTimeOrderedEpoch().toString())
                .orderId(orderId)
                .status(LockStatus.LOCKED)
                .createdAt(now)
                .expireAt(now + lockTimeoutSeconds)
                .build();
    }

    public void addItem(String skuId, LocalDate invDate, int quantity) {
        InventoryLockItem item = InventoryLockItem.create(this.lockId, skuId, invDate, quantity);
        this.items.add(item);
    }

    public void confirm() {
        if (this.status == LockStatus.CONFIRMED) {
            return;
        }
        if (this.status != LockStatus.LOCKED) {
            throw new IllegalStateException("Lock " + lockId + " is in status " + status + ", cannot confirm");
        }
        this.status = LockStatus.CONFIRMED;
    }

    public void release() {
        if (this.status == LockStatus.RELEASED || this.status == LockStatus.EXPIRED) {
            return;
        }
        if (this.status != LockStatus.LOCKED) {
            throw new IllegalStateException("Lock " + lockId + " is in status " + status + ", cannot release");
        }
        this.status = LockStatus.RELEASED;
    }

    public boolean isAlreadyConfirmed() {
        return this.status == LockStatus.CONFIRMED;
    }

    public boolean isAlreadyReleased() {
        return this.status == LockStatus.RELEASED || this.status == LockStatus.EXPIRED;
    }
}
