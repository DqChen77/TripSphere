package org.tripsphere.order.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.tripsphere.order.model.OrderEntity;

public interface OrderRepository extends JpaRepository<OrderEntity, String> {

    Optional<OrderEntity> findByOrderNo(String orderNo);

    Page<OrderEntity> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    Page<OrderEntity> findByUserIdAndStatusOrderByCreatedAtDesc(
            String userId, String status, Pageable pageable);

    List<OrderEntity> findByStatusAndExpireAtLessThan(String status, long expireAt);
}
