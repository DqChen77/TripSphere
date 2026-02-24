package org.tripsphere.inventory.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.tripsphere.inventory.model.DailyInventoryEntity;

public interface DailyInventoryRepository extends JpaRepository<DailyInventoryEntity, Long> {

    Optional<DailyInventoryEntity> findBySkuIdAndInvDate(String skuId, LocalDate invDate);

    List<DailyInventoryEntity> findBySkuIdAndInvDateBetweenOrderByInvDateAsc(
            String skuId, LocalDate startDate, LocalDate endDate);
}
