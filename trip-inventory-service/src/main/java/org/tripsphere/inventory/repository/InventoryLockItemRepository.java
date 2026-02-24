package org.tripsphere.inventory.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.tripsphere.inventory.model.InventoryLockItemEntity;

public interface InventoryLockItemRepository extends JpaRepository<InventoryLockItemEntity, Long> {

    List<InventoryLockItemEntity> findByLockId(String lockId);
}
