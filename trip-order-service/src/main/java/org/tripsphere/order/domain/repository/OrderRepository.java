package org.tripsphere.order.domain.repository;

import java.util.Optional;
import org.tripsphere.order.application.dto.ListOrdersQuery;
import org.tripsphere.order.application.dto.OrderPage;
import org.tripsphere.order.domain.model.Order;

public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(String id);

    OrderPage findByUserIdWithFilters(ListOrdersQuery query);

    long getNextOrderSequence();

    void createSequenceIfNotExists();
}
