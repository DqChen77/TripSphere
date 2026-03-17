package org.tripsphere.order.adapter.inbound.grpc.mapper;

import java.time.LocalDate;
import org.springframework.stereotype.Component;
import org.tripsphere.common.v1.Date;

@Component
public class DateProtoMapper {

    public LocalDate toDomain(Date proto) {
        if (proto == null) return null;
        if (proto.getYear() == 0 && proto.getMonth() == 0 && proto.getDay() == 0) return null;
        return LocalDate.of(proto.getYear(), proto.getMonth(), proto.getDay());
    }

    public Date toProto(LocalDate date) {
        if (date == null) return Date.getDefaultInstance();
        return Date.newBuilder()
                .setYear(date.getYear())
                .setMonth(date.getMonthValue())
                .setDay(date.getDayOfMonth())
                .build();
    }
}
