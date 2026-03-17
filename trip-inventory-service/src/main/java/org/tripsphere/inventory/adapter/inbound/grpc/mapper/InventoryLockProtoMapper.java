package org.tripsphere.inventory.adapter.inbound.grpc.mapper;

import com.google.protobuf.Timestamp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.tripsphere.inventory.domain.model.InventoryLock;
import org.tripsphere.inventory.domain.model.InventoryLockItem;
import org.tripsphere.inventory.domain.model.LockStatus;
import org.tripsphere.inventory.v1.LockItem;

@Component
@RequiredArgsConstructor
public class InventoryLockProtoMapper {

    private final DateProtoMapper dateMapper;

    public org.tripsphere.inventory.v1.InventoryLock toProto(InventoryLock domain) {
        if (domain == null) return null;
        org.tripsphere.inventory.v1.InventoryLock.Builder builder =
                org.tripsphere.inventory.v1.InventoryLock.newBuilder()
                        .setLockId(domain.getLockId())
                        .setOrderId(domain.getOrderId())
                        .setStatus(mapStatus(domain.getStatus()))
                        .setCreatedAt(epochToTimestamp(domain.getCreatedAt()))
                        .setExpireAt(epochToTimestamp(domain.getExpireAt()));

        if (domain.getItems() != null) {
            for (InventoryLockItem item : domain.getItems()) {
                builder.addItems(LockItem.newBuilder()
                        .setSkuId(item.getSkuId())
                        .setDate(dateMapper.toProto(item.getInvDate()))
                        .setQuantity(item.getQuantity())
                        .build());
            }
        }
        return builder.build();
    }

    private org.tripsphere.inventory.v1.LockStatus mapStatus(LockStatus status) {
        return switch (status) {
            case LOCKED -> org.tripsphere.inventory.v1.LockStatus.LOCK_STATUS_LOCKED;
            case CONFIRMED -> org.tripsphere.inventory.v1.LockStatus.LOCK_STATUS_CONFIRMED;
            case RELEASED -> org.tripsphere.inventory.v1.LockStatus.LOCK_STATUS_RELEASED;
            case EXPIRED -> org.tripsphere.inventory.v1.LockStatus.LOCK_STATUS_EXPIRED;
        };
    }

    private Timestamp epochToTimestamp(long epochSecond) {
        return Timestamp.newBuilder().setSeconds(epochSecond).build();
    }
}
