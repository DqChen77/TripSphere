package org.tripsphere.order.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.tripsphere.order.model.OrderItemEntity;

public interface OrderItemRepository extends JpaRepository<OrderItemEntity, String> {

    List<OrderItemEntity> findByOrderId(String orderId);
}
