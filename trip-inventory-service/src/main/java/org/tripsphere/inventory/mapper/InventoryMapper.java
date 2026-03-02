package org.tripsphere.inventory.mapper;

import com.google.protobuf.Timestamp;
import java.time.LocalDate;
import java.util.List;
import org.mapstruct.*;
import org.mapstruct.factory.Mappers;
import org.tripsphere.common.v1.Date;
import org.tripsphere.common.v1.Money;
import org.tripsphere.inventory.model.DailyInventoryEntity;
import org.tripsphere.inventory.model.InventoryLockEntity;
import org.tripsphere.inventory.model.InventoryLockItemEntity;
import org.tripsphere.inventory.v1.DailyInventory;
import org.tripsphere.inventory.v1.InventoryLock;
import org.tripsphere.inventory.v1.LockItem;
import org.tripsphere.inventory.v1.LockStatus;

@Mapper(
        collectionMappingStrategy = CollectionMappingStrategy.ADDER_PREFERRED,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
public interface InventoryMapper {
    InventoryMapper INSTANCE = Mappers.getMapper(InventoryMapper.class);

    default DailyInventory toProto(DailyInventoryEntity entity) {
        if (entity == null) return null;
        DailyInventory.Builder builder =
                DailyInventory.newBuilder()
                        .setSkuId(entity.getSkuId())
                        .setDate(localDateToProto(entity.getInvDate()))
                        .setTotalQuantity(entity.getTotalQty())
                        .setAvailableQuantity(entity.getAvailableQty())
                        .setLockedQuantity(entity.getLockedQty())
                        .setSoldQuantity(entity.getSoldQty())
                        .setPrice(
                                Money.newBuilder()
                                        .setCurrency(entity.getPriceCurrency())
                                        .setUnits(entity.getPriceUnits())
                                        .setNanos(entity.getPriceNanos())
                                        .build());
        return builder.build();
    }

    default List<DailyInventory> toProtoList(List<DailyInventoryEntity> entities) {
        if (entities == null) return List.of();
        return entities.stream().map(this::toProto).toList();
    }

    default InventoryLock toLockProto(InventoryLockEntity entity) {
        if (entity == null) return null;
        InventoryLock.Builder builder =
                InventoryLock.newBuilder()
                        .setLockId(entity.getLockId())
                        .setOrderId(entity.getOrderId())
                        .setStatus(stringToLockStatus(entity.getStatus()))
                        .setCreatedAt(epochSecondToTimestamp(entity.getCreatedAt()))
                        .setExpireAt(epochSecondToTimestamp(entity.getExpireAt()));

        if (entity.getItems() != null) {
            for (InventoryLockItemEntity item : entity.getItems()) {
                builder.addItems(
                        LockItem.newBuilder()
                                .setSkuId(item.getSkuId())
                                .setDate(localDateToProto(item.getInvDate()))
                                .setQuantity(item.getQuantity())
                                .build());
            }
        }
        return builder.build();
    }

    default LocalDate protoToLocalDate(Date date) {
        if (date == null) return null;
        return LocalDate.of(date.getYear(), date.getMonth(), date.getDay());
    }

    default Date localDateToProto(LocalDate date) {
        if (date == null) return Date.getDefaultInstance();
        return Date.newBuilder()
                .setYear(date.getYear())
                .setMonth(date.getMonthValue())
                .setDay(date.getDayOfMonth())
                .build();
    }

    default String lockStatusToString(LockStatus status) {
        return switch (status) {
            case LOCK_STATUS_LOCKED -> "LOCKED";
            case LOCK_STATUS_CONFIRMED -> "CONFIRMED";
            case LOCK_STATUS_RELEASED -> "RELEASED";
            case LOCK_STATUS_EXPIRED -> "EXPIRED";
            default -> "LOCKED";
        };
    }

    default LockStatus stringToLockStatus(String status) {
        if (status == null) return LockStatus.LOCK_STATUS_UNSPECIFIED;
        return switch (status) {
            case "LOCKED" -> LockStatus.LOCK_STATUS_LOCKED;
            case "CONFIRMED" -> LockStatus.LOCK_STATUS_CONFIRMED;
            case "RELEASED" -> LockStatus.LOCK_STATUS_RELEASED;
            case "EXPIRED" -> LockStatus.LOCK_STATUS_EXPIRED;
            default -> LockStatus.LOCK_STATUS_UNSPECIFIED;
        };
    }

    default Timestamp epochSecondToTimestamp(long epochSecond) {
        return Timestamp.newBuilder().setSeconds(epochSecond).build();
    }

    default long timestampToEpochSecond(Timestamp ts) {
        return (ts == null) ? 0L : ts.getSeconds();
    }
}
