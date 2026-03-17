package org.tripsphere.inventory.adapter.inbound.grpc.mapper;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.tripsphere.inventory.domain.model.DailyInventory;

@Component
@RequiredArgsConstructor
public class DailyInventoryProtoMapper {

    private final DateProtoMapper dateMapper;
    private final MoneyProtoMapper moneyMapper;

    public org.tripsphere.inventory.v1.DailyInventory toProto(DailyInventory domain) {
        if (domain == null) return null;
        return org.tripsphere.inventory.v1.DailyInventory.newBuilder()
                .setSkuId(domain.getSkuId())
                .setDate(dateMapper.toProto(domain.getInvDate()))
                .setTotalQuantity(domain.getTotalQty())
                .setAvailableQuantity(domain.getAvailableQty())
                .setLockedQuantity(domain.getLockedQty())
                .setSoldQuantity(domain.getSoldQty())
                .setPrice(moneyMapper.toProto(domain.getPrice()))
                .build();
    }

    public List<org.tripsphere.inventory.v1.DailyInventory> toProtos(List<DailyInventory> domains) {
        if (domains == null) return List.of();
        return domains.stream().map(this::toProto).toList();
    }
}
