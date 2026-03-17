package org.tripsphere.order.adapter.outbound.persistence.mapper;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.tripsphere.order.adapter.outbound.persistence.entity.OrderEntity;
import org.tripsphere.order.adapter.outbound.persistence.entity.OrderItemEntity;
import org.tripsphere.order.domain.model.*;

@Component
public class OrderEntityMapper {

    public OrderEntity toEntity(Order domain) {
        if (domain == null) return null;
        return OrderEntity.builder()
                .id(domain.getId())
                .orderNo(domain.getOrderNo())
                .userId(domain.getUserId())
                .status(domain.getStatus() != null ? domain.getStatus().name() : "PENDING_PAYMENT")
                .type(domain.getType() != null ? domain.getType().name() : "UNSPECIFIED")
                .resourceId(domain.getResourceId())
                .totalCurrency(
                        domain.getTotalAmount() != null
                                ? domain.getTotalAmount().currency()
                                : "CNY")
                .totalUnits(
                        domain.getTotalAmount() != null
                                ? domain.getTotalAmount().units()
                                : 0)
                .totalNanos(
                        domain.getTotalAmount() != null
                                ? domain.getTotalAmount().nanos()
                                : 0)
                .contactName(domain.getContact() != null ? domain.getContact().name() : null)
                .contactPhone(domain.getContact() != null ? domain.getContact().phone() : null)
                .contactEmail(domain.getContact() != null ? domain.getContact().email() : null)
                .sourceChannel(domain.getSource() != null ? domain.getSource().channel() : null)
                .sourceAgentId(domain.getSource() != null ? domain.getSource().agentId() : null)
                .sourceSession(domain.getSource() != null ? domain.getSource().sessionId() : null)
                .cancelReason(domain.getCancelReason())
                .expireAt(domain.getExpireAt())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .paidAt(domain.getPaidAt())
                .cancelledAt(domain.getCancelledAt())
                .build();
    }

    public Order toDomain(OrderEntity entity, List<OrderItemEntity> itemEntities) {
        if (entity == null) return null;
        return Order.builder()
                .id(entity.getId())
                .orderNo(entity.getOrderNo())
                .userId(entity.getUserId())
                .status(OrderStatus.valueOf(entity.getStatus()))
                .type(OrderType.valueOf(entity.getType()))
                .resourceId(entity.getResourceId())
                .totalAmount(new Money(entity.getTotalCurrency(), entity.getTotalUnits(), entity.getTotalNanos()))
                .contact(new ContactInfo(entity.getContactName(), entity.getContactPhone(), entity.getContactEmail()))
                .source(new OrderSource(
                        entity.getSourceChannel(), entity.getSourceAgentId(), entity.getSourceSession()))
                .cancelReason(entity.getCancelReason())
                .expireAt(entity.getExpireAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .paidAt(entity.getPaidAt())
                .cancelledAt(entity.getCancelledAt())
                .items(
                        itemEntities != null
                                ? new ArrayList<>(itemEntities.stream()
                                        .map(this::toItemDomain)
                                        .toList())
                                : new ArrayList<>())
                .build();
    }

    public OrderItemEntity toItemEntity(OrderItem domain) {
        if (domain == null) return null;
        return OrderItemEntity.builder()
                .id(domain.getId())
                .orderId(domain.getOrderId())
                .spuId(domain.getSpuId())
                .skuId(domain.getSkuId())
                .productName(domain.getProductName())
                .skuName(domain.getSkuName())
                .resourceType(domain.getResourceType())
                .resourceId(domain.getResourceId())
                .spuImage(domain.getSpuImage())
                .spuDescription(domain.getSpuDescription())
                .skuAttributes(domain.getSkuAttributes())
                .itemDate(domain.getItemDate())
                .endDate(domain.getEndDate())
                .quantity(domain.getQuantity())
                .unitPriceCcy(
                        domain.getUnitPrice() != null ? domain.getUnitPrice().currency() : null)
                .unitPriceUnits(
                        domain.getUnitPrice() != null ? domain.getUnitPrice().units() : null)
                .unitPriceNanos(
                        domain.getUnitPrice() != null ? domain.getUnitPrice().nanos() : null)
                .subtotalCcy(domain.getSubtotal() != null ? domain.getSubtotal().currency() : null)
                .subtotalUnits(
                        domain.getSubtotal() != null ? domain.getSubtotal().units() : null)
                .subtotalNanos(
                        domain.getSubtotal() != null ? domain.getSubtotal().nanos() : null)
                .invLockId(domain.getInvLockId())
                .build();
    }

    public OrderItem toItemDomain(OrderItemEntity entity) {
        if (entity == null) return null;
        return OrderItem.builder()
                .id(entity.getId())
                .orderId(entity.getOrderId())
                .spuId(entity.getSpuId())
                .skuId(entity.getSkuId())
                .productName(entity.getProductName())
                .skuName(entity.getSkuName())
                .resourceType(entity.getResourceType())
                .resourceId(entity.getResourceId())
                .spuImage(entity.getSpuImage())
                .spuDescription(entity.getSpuDescription())
                .skuAttributes(entity.getSkuAttributes())
                .itemDate(entity.getItemDate())
                .endDate(entity.getEndDate())
                .quantity(entity.getQuantity())
                .unitPrice(
                        entity.getUnitPriceCcy() != null
                                ? new Money(
                                        entity.getUnitPriceCcy(),
                                        entity.getUnitPriceUnits(),
                                        entity.getUnitPriceNanos())
                                : null)
                .subtotal(
                        entity.getSubtotalCcy() != null
                                ? new Money(
                                        entity.getSubtotalCcy(), entity.getSubtotalUnits(), entity.getSubtotalNanos())
                                : null)
                .invLockId(entity.getInvLockId())
                .build();
    }

    public List<OrderItemEntity> toItemEntities(List<OrderItem> items) {
        if (items == null) return List.of();
        return items.stream().map(this::toItemEntity).toList();
    }
}
