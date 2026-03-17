package org.tripsphere.inventory.domain.model;

import com.github.f4b6a3.uuid.UuidCreator;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class InventoryLockItem {

    private String id;
    private String lockId;
    private String skuId;
    private LocalDate invDate;
    private int quantity;

    public static InventoryLockItem create(String lockId, String skuId, LocalDate invDate, int quantity) {
        return InventoryLockItem.builder()
                .id(UuidCreator.getTimeOrderedEpoch().toString())
                .lockId(lockId)
                .skuId(skuId)
                .invDate(invDate)
                .quantity(quantity)
                .build();
    }
}
