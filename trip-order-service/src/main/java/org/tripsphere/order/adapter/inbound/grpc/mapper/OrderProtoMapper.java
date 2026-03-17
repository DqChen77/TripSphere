package org.tripsphere.order.adapter.inbound.grpc.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.tripsphere.order.domain.model.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderProtoMapper {

    private final DateProtoMapper dateMapper;
    private final MoneyProtoMapper moneyMapper;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    public org.tripsphere.order.v1.Order toProto(Order domain) {
        if (domain == null) return null;
        org.tripsphere.order.v1.Order.Builder builder = org.tripsphere.order.v1.Order.newBuilder()
                .setId(domain.getId())
                .setOrderNo(domain.getOrderNo())
                .setUserId(domain.getUserId())
                .setStatus(mapStatus(domain.getStatus()))
                .setType(mapType(domain.getType()))
                .setTotalAmount(moneyMapper.toProto(domain.getTotalAmount()));

        if (domain.getResourceId() != null) builder.setResourceId(domain.getResourceId());
        if (domain.getCancelReason() != null) builder.setCancelReason(domain.getCancelReason());
        if (domain.getExpireAt() != null) builder.setExpireAt(epochToTimestamp(domain.getExpireAt()));
        builder.setCreatedAt(epochToTimestamp(domain.getCreatedAt()));
        builder.setUpdatedAt(epochToTimestamp(domain.getUpdatedAt()));
        if (domain.getPaidAt() != null) builder.setPaidAt(epochToTimestamp(domain.getPaidAt()));
        if (domain.getCancelledAt() != null) builder.setCancelledAt(epochToTimestamp(domain.getCancelledAt()));

        if (domain.getContact() != null) {
            org.tripsphere.order.v1.ContactInfo.Builder contactBuilder =
                    org.tripsphere.order.v1.ContactInfo.newBuilder();
            if (domain.getContact().name() != null)
                contactBuilder.setName(domain.getContact().name());
            if (domain.getContact().phone() != null)
                contactBuilder.setPhone(domain.getContact().phone());
            if (domain.getContact().email() != null)
                contactBuilder.setEmail(domain.getContact().email());
            builder.setContact(contactBuilder.build());
        }

        if (domain.getSource() != null) {
            org.tripsphere.order.v1.OrderSource.Builder sourceBuilder =
                    org.tripsphere.order.v1.OrderSource.newBuilder();
            if (domain.getSource().channel() != null)
                sourceBuilder.setChannel(domain.getSource().channel());
            if (domain.getSource().agentId() != null)
                sourceBuilder.setAgentId(domain.getSource().agentId());
            if (domain.getSource().sessionId() != null)
                sourceBuilder.setSessionId(domain.getSource().sessionId());
            builder.setSource(sourceBuilder.build());
        }

        if (domain.getItems() != null) {
            domain.getItems().forEach(item -> builder.addItems(toItemProto(item)));
        }

        return builder.build();
    }

    public List<org.tripsphere.order.v1.Order> toProtos(List<Order> domains) {
        if (domains == null) return List.of();
        return domains.stream().map(this::toProto).toList();
    }

    private org.tripsphere.order.v1.OrderItem toItemProto(OrderItem item) {
        org.tripsphere.order.v1.OrderItem.Builder builder = org.tripsphere.order.v1.OrderItem.newBuilder()
                .setId(item.getId())
                .setSpuId(item.getSpuId())
                .setSkuId(item.getSkuId())
                .setQuantity(item.getQuantity());

        if (item.getProductName() != null) builder.setSpuName(item.getProductName());
        if (item.getSkuName() != null) builder.setSkuName(item.getSkuName());
        if (item.getResourceId() != null) builder.setResourceId(item.getResourceId());
        if (item.getSpuImage() != null) builder.setSpuImage(item.getSpuImage());
        if (item.getSpuDescription() != null) builder.setSpuDescription(item.getSpuDescription());
        if (item.getItemDate() != null) builder.setDate(dateMapper.toProto(item.getItemDate()));
        if (item.getEndDate() != null) builder.setEndDate(dateMapper.toProto(item.getEndDate()));
        if (item.getUnitPrice() != null) builder.setUnitPrice(moneyMapper.toProto(item.getUnitPrice()));
        if (item.getSubtotal() != null) builder.setSubtotal(moneyMapper.toProto(item.getSubtotal()));
        if (item.getInvLockId() != null) builder.setInventoryLockId(item.getInvLockId());
        if (item.getSkuAttributes() != null) builder.setSkuAttributes(mapToStruct(item.getSkuAttributes()));

        return builder.build();
    }

    public org.tripsphere.order.v1.OrderStatus mapStatus(OrderStatus status) {
        if (status == null) return org.tripsphere.order.v1.OrderStatus.ORDER_STATUS_UNSPECIFIED;
        return switch (status) {
            case PENDING_PAYMENT -> org.tripsphere.order.v1.OrderStatus.ORDER_STATUS_PENDING_PAYMENT;
            case PAID -> org.tripsphere.order.v1.OrderStatus.ORDER_STATUS_PAID;
            case COMPLETED -> org.tripsphere.order.v1.OrderStatus.ORDER_STATUS_COMPLETED;
            case CANCELLED -> org.tripsphere.order.v1.OrderStatus.ORDER_STATUS_CANCELLED;
        };
    }

    public OrderStatus mapStatusToDomain(org.tripsphere.order.v1.OrderStatus proto) {
        return switch (proto) {
            case ORDER_STATUS_PENDING_PAYMENT -> OrderStatus.PENDING_PAYMENT;
            case ORDER_STATUS_PAID -> OrderStatus.PAID;
            case ORDER_STATUS_COMPLETED -> OrderStatus.COMPLETED;
            case ORDER_STATUS_CANCELLED -> OrderStatus.CANCELLED;
            default -> null;
        };
    }

    public org.tripsphere.order.v1.OrderType mapType(OrderType type) {
        if (type == null) return org.tripsphere.order.v1.OrderType.ORDER_TYPE_UNSPECIFIED;
        return switch (type) {
            case ATTRACTION -> org.tripsphere.order.v1.OrderType.ORDER_TYPE_ATTRACTION;
            case HOTEL -> org.tripsphere.order.v1.OrderType.ORDER_TYPE_HOTEL;
            case FLIGHT -> org.tripsphere.order.v1.OrderType.ORDER_TYPE_FLIGHT;
            case TRAIN -> org.tripsphere.order.v1.OrderType.ORDER_TYPE_TRAIN;
            case UNSPECIFIED -> org.tripsphere.order.v1.OrderType.ORDER_TYPE_UNSPECIFIED;
        };
    }

    public OrderType mapTypeToDomain(org.tripsphere.order.v1.OrderType proto) {
        return switch (proto) {
            case ORDER_TYPE_ATTRACTION -> OrderType.ATTRACTION;
            case ORDER_TYPE_HOTEL -> OrderType.HOTEL;
            case ORDER_TYPE_FLIGHT -> OrderType.FLIGHT;
            case ORDER_TYPE_TRAIN -> OrderType.TRAIN;
            default -> null;
        };
    }

    private Timestamp epochToTimestamp(long epochSecond) {
        return Timestamp.newBuilder().setSeconds(epochSecond).build();
    }

    @SuppressWarnings("unchecked")
    private Struct mapToStruct(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return Struct.getDefaultInstance();
        Struct.Builder structBuilder = Struct.newBuilder();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            structBuilder.putFields(entry.getKey(), toProtoValue(entry.getValue()));
        }
        return structBuilder.build();
    }

    private Value toProtoValue(Object obj) {
        if (obj == null)
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        if (obj instanceof String s) return Value.newBuilder().setStringValue(s).build();
        if (obj instanceof Number n)
            return Value.newBuilder().setNumberValue(n.doubleValue()).build();
        if (obj instanceof Boolean b) return Value.newBuilder().setBoolValue(b).build();
        return Value.newBuilder().setStringValue(obj.toString()).build();
    }
}
