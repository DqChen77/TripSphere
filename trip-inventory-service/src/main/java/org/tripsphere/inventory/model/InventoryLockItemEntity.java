package org.tripsphere.inventory.model;

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
        name = "inventory_lock_item",
        indexes = {@Index(name = "idx_lock_id", columnList = "lockId")})
public class InventoryLockItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String lockId;

    @Column(nullable = false, length = 64)
    private String skuId;

    @Column(nullable = false)
    private LocalDate invDate;

    @Column(nullable = false)
    private int quantity;
}
