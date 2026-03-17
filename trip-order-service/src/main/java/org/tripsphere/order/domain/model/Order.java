package org.tripsphere.order.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class Order {

    private String id;
    private String orderNo;
    private String userId;
    private OrderStatus status;
    private OrderType type;
    private String resourceId;
    private Money totalAmount;
    private ContactInfo contact;
    private OrderSource source;
    private String cancelReason;
    private Long expireAt;
    private long createdAt;
    private long updatedAt;
    private Long paidAt;
    private Long cancelledAt;

    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    public static Order create(
            String id,
            String orderNo,
            String userId,
            OrderType type,
            String resourceId,
            Money totalAmount,
            ContactInfo contact,
            OrderSource source,
            long expireAt,
            List<OrderItem> items) {
        long now = Instant.now().getEpochSecond();
        return Order.builder()
                .id(id)
                .orderNo(orderNo)
                .userId(userId)
                .status(OrderStatus.PENDING_PAYMENT)
                .type(type)
                .resourceId(resourceId)
                .totalAmount(totalAmount)
                .contact(contact)
                .source(source)
                .expireAt(expireAt)
                .createdAt(now)
                .updatedAt(now)
                .items(items != null ? new ArrayList<>(items) : new ArrayList<>())
                .build();
    }

    public void cancel(String reason) {
        if (this.status != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("Order " + id + " is in status " + status + ", cannot cancel");
        }
        long now = Instant.now().getEpochSecond();
        this.status = OrderStatus.CANCELLED;
        this.cancelReason = reason;
        this.cancelledAt = now;
        this.updatedAt = now;
    }

    public void confirmPayment() {
        if (this.status != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("Order " + id + " is in status " + status + ", cannot confirm payment");
        }
        if (isExpired()) {
            throw new IllegalStateException("Order " + id + " has expired");
        }
        long now = Instant.now().getEpochSecond();
        this.status = OrderStatus.PAID;
        this.paidAt = now;
        this.updatedAt = now;
    }

    public boolean isExpired() {
        return expireAt != null && Instant.now().getEpochSecond() > expireAt;
    }

    public Set<String> getDistinctLockIds() {
        return items.stream()
                .map(OrderItem::getInvLockId)
                .filter(id -> id != null && !id.isEmpty())
                .collect(Collectors.toSet());
    }
}
