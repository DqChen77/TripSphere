package org.tripsphere.inventory.domain.model;

import com.github.f4b6a3.uuid.UuidCreator;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DailyInventory {

    private String id;
    private String skuId;
    private LocalDate invDate;
    private int totalQty;
    private int availableQty;
    private int lockedQty;
    private int soldQty;
    private Money price;
    private Instant updatedAt;

    public static DailyInventory create(String skuId, LocalDate invDate, int totalQty, Money price) {
        return DailyInventory.builder()
                .id(UuidCreator.getTimeOrderedEpoch().toString())
                .skuId(skuId)
                .invDate(invDate)
                .totalQty(totalQty)
                .availableQty(totalQty)
                .lockedQty(0)
                .soldQty(0)
                .price(price)
                .updatedAt(Instant.now())
                .build();
    }

    public void updateTotal(int newTotal) {
        int newAvailable = newTotal - this.lockedQty - this.soldQty;
        if (newAvailable < 0) {
            throw new IllegalArgumentException(
                    "Cannot set total_quantity to " + newTotal + ": would result in negative available quantity");
        }
        this.totalQty = newTotal;
        this.availableQty = newAvailable;
        this.updatedAt = Instant.now();
    }

    public void updatePrice(Money price) {
        this.price = price;
        this.updatedAt = Instant.now();
    }

    public void lock(int qty) {
        if (this.availableQty < qty) {
            throw new IllegalStateException("Insufficient inventory: requested=" + qty + ", available=" + availableQty);
        }
        this.availableQty -= qty;
        this.lockedQty += qty;
        this.updatedAt = Instant.now();
    }

    public void confirmSold(int qty) {
        this.lockedQty -= qty;
        this.soldQty += qty;
        this.updatedAt = Instant.now();
    }

    public void release(int qty) {
        this.lockedQty -= qty;
        this.availableQty += qty;
        this.updatedAt = Instant.now();
    }
}
