package org.tripsphere.inventory.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.tripsphere.inventory.model.InventoryLockEntity;

public interface InventoryLockRepository extends JpaRepository<InventoryLockEntity, String> {

    Optional<InventoryLockEntity> findByLockId(String lockId);

    Optional<InventoryLockEntity> findByOrderId(String orderId);

    List<InventoryLockEntity> findByStatusAndExpireAtLessThan(String status, long expireAt);
}
