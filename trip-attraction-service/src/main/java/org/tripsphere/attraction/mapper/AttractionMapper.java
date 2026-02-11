package org.tripsphere.attraction.mapper;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.mapstruct.*;
import org.mapstruct.factory.Mappers;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.tripsphere.attraction.model.AttractionDoc;
import org.tripsphere.attraction.model.MoneyDoc;
import org.tripsphere.attraction.model.OpenRuleDoc;
import org.tripsphere.attraction.model.OpeningHoursDoc;
import org.tripsphere.attraction.model.TicketInfoDoc;
import org.tripsphere.attraction.model.TimeOfDayDoc;
import org.tripsphere.attraction.model.TimeRangeDoc;
import org.tripsphere.attraction.util.CoordinateTransformUtil;
import org.tripsphere.attraction.v1.Attraction;
import org.tripsphere.attraction.v1.OpenRule;
import org.tripsphere.attraction.v1.OpeningHours;
import org.tripsphere.attraction.v1.TicketInfo;
import org.tripsphere.attraction.v1.TimeRange;
import org.tripsphere.common.v1.DayOfWeek;
import org.tripsphere.common.v1.GeoPoint;
import org.tripsphere.common.v1.Money;
import org.tripsphere.common.v1.TimeOfDay;

@Mapper(
        collectionMappingStrategy = CollectionMappingStrategy.ADDER_PREFERRED,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
public interface AttractionMapper {
    AttractionMapper INSTANCE = Mappers.getMapper(AttractionMapper.class);

    // ===================================================================
    // Main Attraction Mappings
    // ===================================================================

    @Mapping(target = "location", source = "location", qualifiedByName = "toGeoJsonPoint")
    @Mapping(
            target = "openingHours",
            source = "openingHours",
            qualifiedByName = "toOpeningHoursDoc")
    @Mapping(target = "ticketInfo", source = "ticketInfo", qualifiedByName = "toTicketInfoDoc")
    AttractionDoc toDoc(Attraction attraction);

    @Mapping(target = "location", source = "location", qualifiedByName = "toGeoPoint")
    @Mapping(
            target = "openingHours",
            source = "openingHours",
            qualifiedByName = "toOpeningHoursProto")
    @Mapping(target = "ticketInfo", source = "ticketInfo", qualifiedByName = "toTicketInfoProto")
    Attraction toProto(AttractionDoc attractionDoc);

    List<Attraction> toProtoList(List<AttractionDoc> attractionDocs);

    // ===================================================================
    // GeoPoint <-> GeoJsonPoint (with coordinate transformation)
    // ===================================================================

    @Named("toGeoJsonPoint")
    default GeoJsonPoint toGeoJsonPoint(GeoPoint point) {
        if (point == null
                || (point.getLongitude() == 0.0
                        && point.getLatitude() == 0.0
                        && !point.isInitialized())) {
            return null;
        }
        double[] coordinate =
                CoordinateTransformUtil.gcj02ToWgs84(point.getLongitude(), point.getLatitude());
        return new GeoJsonPoint(coordinate[0], coordinate[1]);
    }

    @Named("toGeoPoint")
    default GeoPoint toGeoPoint(GeoJsonPoint geoJsonPoint) {
        if (geoJsonPoint == null) return GeoPoint.getDefaultInstance();
        double[] coordinate =
                CoordinateTransformUtil.wgs84ToGcj02(geoJsonPoint.getX(), geoJsonPoint.getY());
        return GeoPoint.newBuilder().setLongitude(coordinate[0]).setLatitude(coordinate[1]).build();
    }

    // ===================================================================
    // OpeningHours Mappings
    // ===================================================================

    @Named("toOpeningHoursDoc")
    default OpeningHoursDoc toOpeningHoursDoc(OpeningHours proto) {
        if (proto == null || proto.equals(OpeningHours.getDefaultInstance())) {
            return null;
        }
        return OpeningHoursDoc.builder()
                .rules(proto.getRulesList().stream().map(this::toOpenRuleDoc).toList())
                .specialTips(proto.getSpecialTips())
                .build();
    }

    @Named("toOpeningHoursProto")
    default OpeningHours toOpeningHoursProto(OpeningHoursDoc doc) {
        if (doc == null) return OpeningHours.getDefaultInstance();
        OpeningHours.Builder builder = OpeningHours.newBuilder();
        if (doc.getRules() != null) {
            doc.getRules().forEach(rule -> builder.addRules(toOpenRuleProto(rule)));
        }
        if (doc.getSpecialTips() != null) {
            builder.setSpecialTips(doc.getSpecialTips());
        }
        return builder.build();
    }

    // ===================================================================
    // OpenRule Mappings
    // ===================================================================

    default OpenRuleDoc toOpenRuleDoc(OpenRule proto) {
        if (proto == null) return null;
        return OpenRuleDoc.builder()
                .days(proto.getDaysList().stream().map(DayOfWeek::name).toList())
                .timeRanges(proto.getTimeRangesList().stream().map(this::toTimeRangeDoc).toList())
                .note(proto.getNote())
                .build();
    }

    default OpenRule toOpenRuleProto(OpenRuleDoc doc) {
        if (doc == null) return OpenRule.getDefaultInstance();
        OpenRule.Builder builder = OpenRule.newBuilder();
        if (doc.getDays() != null) {
            doc.getDays()
                    .forEach(
                            day -> {
                                try {
                                    builder.addDays(DayOfWeek.valueOf(day));
                                } catch (IllegalArgumentException e) {
                                    // Skip invalid enum values
                                }
                            });
        }
        if (doc.getTimeRanges() != null) {
            doc.getTimeRanges().forEach(tr -> builder.addTimeRanges(toTimeRangeProto(tr)));
        }
        if (doc.getNote() != null) {
            builder.setNote(doc.getNote());
        }
        return builder.build();
    }

    // ===================================================================
    // TimeRange Mappings
    // ===================================================================

    default TimeRangeDoc toTimeRangeDoc(TimeRange proto) {
        if (proto == null) return null;
        return TimeRangeDoc.builder()
                .openTime(toTimeOfDayDoc(proto.getOpenTime()))
                .closeTime(toTimeOfDayDoc(proto.getCloseTime()))
                .lastEntryTime(toTimeOfDayDoc(proto.getLastEntryTime()))
                .build();
    }

    default TimeRange toTimeRangeProto(TimeRangeDoc doc) {
        if (doc == null) return TimeRange.getDefaultInstance();
        TimeRange.Builder builder = TimeRange.newBuilder();
        if (doc.getOpenTime() != null) {
            builder.setOpenTime(toTimeOfDayProto(doc.getOpenTime()));
        }
        if (doc.getCloseTime() != null) {
            builder.setCloseTime(toTimeOfDayProto(doc.getCloseTime()));
        }
        if (doc.getLastEntryTime() != null) {
            builder.setLastEntryTime(toTimeOfDayProto(doc.getLastEntryTime()));
        }
        return builder.build();
    }

    // ===================================================================
    // TimeOfDay Mappings
    // ===================================================================

    default TimeOfDayDoc toTimeOfDayDoc(TimeOfDay proto) {
        if (proto == null || proto.equals(TimeOfDay.getDefaultInstance())) {
            return null;
        }
        return TimeOfDayDoc.builder()
                .hours(proto.getHours())
                .minutes(proto.getMinutes())
                .seconds(proto.getSeconds())
                .nanos(proto.getNanos())
                .build();
    }

    default TimeOfDay toTimeOfDayProto(TimeOfDayDoc doc) {
        if (doc == null) return TimeOfDay.getDefaultInstance();
        return TimeOfDay.newBuilder()
                .setHours(doc.getHours())
                .setMinutes(doc.getMinutes())
                .setSeconds(doc.getSeconds())
                .setNanos(doc.getNanos())
                .build();
    }

    // ===================================================================
    // TicketInfo Mappings
    // ===================================================================

    @Named("toTicketInfoDoc")
    default TicketInfoDoc toTicketInfoDoc(TicketInfo proto) {
        if (proto == null || proto.equals(TicketInfo.getDefaultInstance())) {
            return null;
        }
        return TicketInfoDoc.builder()
                .estimatedPrice(toMoneyDoc(proto.getEstimatedPrice()))
                .metadata(structToMap(proto.getMetadata()))
                .build();
    }

    @Named("toTicketInfoProto")
    default TicketInfo toTicketInfoProto(TicketInfoDoc doc) {
        if (doc == null) return TicketInfo.getDefaultInstance();
        TicketInfo.Builder builder = TicketInfo.newBuilder();
        if (doc.getEstimatedPrice() != null) {
            builder.setEstimatedPrice(toMoneyProto(doc.getEstimatedPrice()));
        }
        if (doc.getMetadata() != null) {
            builder.setMetadata(mapToStruct(doc.getMetadata()));
        }
        return builder.build();
    }

    // ===================================================================
    // Money Mappings
    // ===================================================================

    default MoneyDoc toMoneyDoc(Money proto) {
        if (proto == null || proto.equals(Money.getDefaultInstance())) {
            return null;
        }
        return MoneyDoc.builder()
                .currency(proto.getCurrency())
                .units(proto.getUnits())
                .nanos(proto.getNanos())
                .build();
    }

    default Money toMoneyProto(MoneyDoc doc) {
        if (doc == null) return Money.getDefaultInstance();
        Money.Builder builder = Money.newBuilder();
        if (doc.getCurrency() != null) {
            builder.setCurrency(doc.getCurrency());
        }
        builder.setUnits(doc.getUnits());
        builder.setNanos(doc.getNanos());
        return builder.build();
    }

    // ===================================================================
    // Struct <-> Map Conversions
    // ===================================================================

    default Map<String, Object> structToMap(Struct struct) {
        if (struct == null || struct.equals(Struct.getDefaultInstance())) {
            return null;
        }
        Map<String, Object> map = new HashMap<>();
        struct.getFieldsMap().forEach((key, value) -> map.put(key, valueToObject(value)));
        return map;
    }

    default Struct mapToStruct(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return Struct.getDefaultInstance();
        }
        Struct.Builder builder = Struct.newBuilder();
        map.forEach((key, value) -> builder.putFields(key, objectToValue(value)));
        return builder.build();
    }

    default Object valueToObject(Value value) {
        if (value == null) return null;
        return switch (value.getKindCase()) {
            case NULL_VALUE -> null;
            case NUMBER_VALUE -> value.getNumberValue();
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> value.getBoolValue();
            case STRUCT_VALUE -> structToMap(value.getStructValue());
            case LIST_VALUE -> value.getListValue().getValuesList().stream()
                    .map(this::valueToObject)
                    .collect(Collectors.toList());
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    default Value objectToValue(Object obj) {
        if (obj == null) {
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        }
        if (obj instanceof String s) {
            return Value.newBuilder().setStringValue(s).build();
        }
        if (obj instanceof Number n) {
            return Value.newBuilder().setNumberValue(n.doubleValue()).build();
        }
        if (obj instanceof Boolean b) {
            return Value.newBuilder().setBoolValue(b).build();
        }
        if (obj instanceof Map) {
            return Value.newBuilder()
                    .setStructValue(mapToStruct((Map<String, Object>) obj))
                    .build();
        }
        if (obj instanceof List) {
            ListValue.Builder listBuilder = ListValue.newBuilder();
            ((List<?>) obj).forEach(item -> listBuilder.addValues(objectToValue(item)));
            return Value.newBuilder().setListValue(listBuilder).build();
        }
        // Fallback: convert to string
        return Value.newBuilder().setStringValue(obj.toString()).build();
    }
}
