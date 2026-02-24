package org.tripsphere.inventory.model;

import jakarta.persistence.*;
import java.util.List;
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
        name = "inventory_lock",
        indexes = {
            @Index(name = "idx_order_id", columnList = "orderId"),
            @Index(name = "idx_status_expire", columnList = "status, expireAt")
        })
public class InventoryLockEntity {

    @Id
    @Column(length = 64)
    private String lockId;

    @Column(nullable = false, length = 64)
    private String orderId;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "LOCKED";

    @Column(nullable = false)
    private long createdAt;

    @Column(nullable = false)
    private long expireAt;

    @OneToMany(mappedBy = "lockId", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<InventoryLockItemEntity> items;
}
