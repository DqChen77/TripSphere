package org.tripsphere.order.adapter.inbound.grpc.mapper;

import org.springframework.stereotype.Component;
import org.tripsphere.order.domain.model.Money;

@Component
public class MoneyProtoMapper {

    public org.tripsphere.common.v1.Money toProto(Money domain) {
        if (domain == null) return org.tripsphere.common.v1.Money.getDefaultInstance();
        return org.tripsphere.common.v1.Money.newBuilder()
                .setCurrency(domain.currency())
                .setUnits(domain.units())
                .setNanos(domain.nanos())
                .build();
    }
}
