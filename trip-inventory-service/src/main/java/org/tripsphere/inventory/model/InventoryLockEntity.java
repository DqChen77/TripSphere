package org.tripsphere.inventory.model;

import jakarta.persistence.*;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single lock operation. One order has at most one lock.
 *
 * <p>Relationship: {@code inventory_lock 1 ──▶ N inventory_lock_item}. Each lock item is a single
 * (sku_id, date, quantity) tuple that was locked atomically as part of this operation.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(
        name = "inventory_lock",
        indexes = {@Index(name = "idx_status_expire", columnList = "status, expireAt")})
public class InventoryLockEntity {

    @Id
    @Column(length = 64)
    private String lockId;

    /** Each order has at most one lock — unique constraint enforces idempotency at DB level. */
    @Column(nullable = false, unique = true, length = 64)
    private String orderId;

    @Column(nullable = false, length = 16)
    @Builder.Default
    private String status = "LOCKED";

    @Column(nullable = false)
    private long createdAt;

    @Column(nullable = false)
    private long expireAt;

    /**
     * Lock items are loaded manually via {@code InventoryLockItemRepository.findByLockId()}.
     * Using @Transient because the child entity uses a plain String FK, not a @ManyToOne
     * association, which is incompatible with @OneToMany(mappedBy).
     */
    @Transient private List<InventoryLockItemEntity> items;
}
