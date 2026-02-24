package org.tripsphere.order.service;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.tripsphere.order.v1.ContactInfo;
import org.tripsphere.order.v1.CreateOrderItem;
import org.tripsphere.order.v1.Order;
import org.tripsphere.order.v1.OrderSource;
import org.tripsphere.order.v1.OrderStatus;

public interface OrderService {

    /** Create a new order (Saga orchestration: validate → lock → persist). */
    Order createOrder(
            String userId,
            java.util.List<CreateOrderItem> items,
            ContactInfo contact,
            OrderSource source);

    /** Get order by ID. */
    Optional<Order> getOrder(String orderId);

    /** List user orders with optional status filter and pagination. */
    Page<Order> listUserOrders(String userId, OrderStatus status, int pageSize, String pageToken);

    /** Cancel an order (releases inventory lock). */
    Order cancelOrder(String orderId, String reason);

    /** Confirm payment for an order (confirms inventory lock). */
    Order confirmPayment(String orderId, String paymentMethod);
}
