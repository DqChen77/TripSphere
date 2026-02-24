package org.tripsphere.order.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(
        name = "order_items",
        indexes = {@Index(name = "idx_order_id", columnList = "orderId")})
public class OrderItemEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 64)
    private String orderId;

    @Column(nullable = false, length = 64)
    private String spuId;

    @Column(nullable = false, length = 64)
    private String skuId;

    @Column(length = 256)
    private String productName;

    @Column(length = 256)
    private String skuName;

    @Column(nullable = false)
    private LocalDate itemDate;

    private LocalDate endDate;

    @Column(nullable = false)
    @Builder.Default
    private int quantity = 1;

    @Column(length = 3)
    private String unitPriceCcy;

    private Long unitPriceUnits;

    private Integer unitPriceNanos;

    @Column(length = 3)
    private String subtotalCcy;

    private Long subtotalUnits;

    private Integer subtotalNanos;

    @Column(length = 64)
    private String invLockId;
}
