package org.tripsphere.attraction.mapper;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.mapstruct.*;
import org.mapstruct.factory.Mappers;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.tripsphere.attraction.model.AttractionDoc;
import org.tripsphere.attraction.model.OpenRule;
import org.tripsphere.attraction.model.OpeningHours;
import org.tripsphere.attraction.model.TicketInfo;
import org.tripsphere.attraction.util.CoordinateTransformUtil;
import org.tripsphere.attraction.v1.Attraction;
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
    @Mapping(target = "openingHours", source = "openingHours", qualifiedByName = "toOpeningHours")
    @Mapping(target = "ticketInfo", source = "ticketInfo", qualifiedByName = "toTicketInfo")
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

    @Named("toOpeningHours")
    default OpeningHours toOpeningHours(org.tripsphere.attraction.v1.OpeningHours proto) {
        if (proto == null
                || proto.equals(org.tripsphere.attraction.v1.OpeningHours.getDefaultInstance())) {
            return null;
        }
        return OpeningHours.builder()
                .rules(proto.getRulesList().stream().map(this::toOpenRule).toList())
                .specialTips(proto.getSpecialTips())
                .build();
    }

    @Named("toOpeningHoursProto")
    default org.tripsphere.attraction.v1.OpeningHours toOpeningHoursProto(OpeningHours doc) {
        if (doc == null) return org.tripsphere.attraction.v1.OpeningHours.getDefaultInstance();
        org.tripsphere.attraction.v1.OpeningHours.Builder builder =
                org.tripsphere.attraction.v1.OpeningHours.newBuilder();
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

    default OpenRule toOpenRule(org.tripsphere.attraction.v1.OpenRule proto) {
        if (proto == null) return null;
        return OpenRule.builder()
                .days(proto.getDaysList().stream().map(this::toJavaDayOfWeek).toList())
                .timeRanges(proto.getTimeRangesList().stream().map(this::toTimeRangeDoc).toList())
                .note(proto.getNote())
                .build();
    }

    default org.tripsphere.attraction.v1.OpenRule toOpenRuleProto(OpenRule doc) {
        if (doc == null) return org.tripsphere.attraction.v1.OpenRule.getDefaultInstance();
        org.tripsphere.attraction.v1.OpenRule.Builder builder =
                org.tripsphere.attraction.v1.OpenRule.newBuilder();
        if (doc.getDays() != null) {
            doc.getDays().forEach(day -> builder.addDays(toProtoDayOfWeek(day)));
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
    // DayOfWeek Mappings (java.time.DayOfWeek <-> proto DayOfWeek)
    // ===================================================================

    default java.time.DayOfWeek toJavaDayOfWeek(org.tripsphere.common.v1.DayOfWeek protoDayOfWeek) {
        if (protoDayOfWeek == null) return null;
        return switch (protoDayOfWeek) {
            case DAY_OF_WEEK_MONDAY -> java.time.DayOfWeek.MONDAY;
            case DAY_OF_WEEK_TUESDAY -> java.time.DayOfWeek.TUESDAY;
            case DAY_OF_WEEK_WEDNESDAY -> java.time.DayOfWeek.WEDNESDAY;
            case DAY_OF_WEEK_THURSDAY -> java.time.DayOfWeek.THURSDAY;
            case DAY_OF_WEEK_FRIDAY -> java.time.DayOfWeek.FRIDAY;
            case DAY_OF_WEEK_SATURDAY -> java.time.DayOfWeek.SATURDAY;
            case DAY_OF_WEEK_SUNDAY -> java.time.DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    default org.tripsphere.common.v1.DayOfWeek toProtoDayOfWeek(java.time.DayOfWeek javaDayOfWeek) {
        if (javaDayOfWeek == null)
            return org.tripsphere.common.v1.DayOfWeek.DAY_OF_WEEK_UNSPECIFIED;
        return switch (javaDayOfWeek) {
            case MONDAY -> org.tripsphere.common.v1.DayOfWeek.DAY_OF_WEEK_MONDAY;
            case TUESDAY -> org.tripsphere.common.v1.DayOfWeek.DAY_OF_WEEK_TUESDAY;
            case WEDNESDAY -> org.tripsphere.common.v1.DayOfWeek.DAY_OF_WEEK_WEDNESDAY;
            case THURSDAY -> org.tripsphere.common.v1.DayOfWeek.DAY_OF_WEEK_THURSDAY;
            case FRIDAY -> org.tripsphere.common.v1.DayOfWeek.DAY_OF_WEEK_FRIDAY;
            case SATURDAY -> org.tripsphere.common.v1.DayOfWeek.DAY_OF_WEEK_SATURDAY;
            case SUNDAY -> org.tripsphere.common.v1.DayOfWeek.DAY_OF_WEEK_SUNDAY;
        };
    }

    // ===================================================================
    // TimeRange Mappings (using OpenRule.TimeRange record with LocalTime)
    // ===================================================================

    default OpenRule.TimeRange toTimeRangeDoc(org.tripsphere.attraction.v1.TimeRange proto) {
        if (proto == null) return null;
        return new OpenRule.TimeRange(
                toLocalTime(proto.getOpenTime()),
                toLocalTime(proto.getCloseTime()),
                toLocalTime(proto.getLastEntryTime()));
    }

    default org.tripsphere.attraction.v1.TimeRange toTimeRangeProto(OpenRule.TimeRange doc) {
        if (doc == null) return org.tripsphere.attraction.v1.TimeRange.getDefaultInstance();
        org.tripsphere.attraction.v1.TimeRange.Builder builder =
                org.tripsphere.attraction.v1.TimeRange.newBuilder();
        if (doc.openTime() != null) {
            builder.setOpenTime(toTimeOfDayProto(doc.openTime()));
        }
        if (doc.closeTime() != null) {
            builder.setCloseTime(toTimeOfDayProto(doc.closeTime()));
        }
        if (doc.lastEntryTime() != null) {
            builder.setLastEntryTime(toTimeOfDayProto(doc.lastEntryTime()));
        }
        return builder.build();
    }

    // ===================================================================
    // LocalTime <-> TimeOfDay Mappings
    // ===================================================================

    default LocalTime toLocalTime(TimeOfDay proto) {
        if (proto == null || proto.equals(TimeOfDay.getDefaultInstance())) {
            return null;
        }
        return LocalTime.of(
                proto.getHours(), proto.getMinutes(), proto.getSeconds(), proto.getNanos());
    }

    default TimeOfDay toTimeOfDayProto(LocalTime localTime) {
        if (localTime == null) return TimeOfDay.getDefaultInstance();
        return TimeOfDay.newBuilder()
                .setHours(localTime.getHour())
                .setMinutes(localTime.getMinute())
                .setSeconds(localTime.getSecond())
                .setNanos(localTime.getNano())
                .build();
    }

    // ===================================================================
    // TicketInfo Mappings
    // ===================================================================

    @Named("toTicketInfo")
    default TicketInfo toTicketInfo(org.tripsphere.attraction.v1.TicketInfo proto) {
        if (proto == null
                || proto.equals(org.tripsphere.attraction.v1.TicketInfo.getDefaultInstance())) {
            return null;
        }
        return TicketInfo.builder()
                .estimatedPrice(toMoney(proto.getEstimatedPrice()))
                .metadata(structToMap(proto.getMetadata()))
                .build();
    }

    @Named("toTicketInfoProto")
    default org.tripsphere.attraction.v1.TicketInfo toTicketInfoProto(TicketInfo doc) {
        if (doc == null) return org.tripsphere.attraction.v1.TicketInfo.getDefaultInstance();
        org.tripsphere.attraction.v1.TicketInfo.Builder builder =
                org.tripsphere.attraction.v1.TicketInfo.newBuilder();
        if (doc.getEstimatedPrice() != null) {
            builder.setEstimatedPrice(toMoneyProto(doc.getEstimatedPrice()));
        }
        if (doc.getMetadata() != null) {
            builder.setMetadata(mapToStruct(doc.getMetadata()));
        }
        return builder.build();
    }

    // ===================================================================
    // Money Mappings (using TicketInfo.Money record with Currency & BigDecimal)
    // ===================================================================

    default TicketInfo.Money toMoney(Money proto) {
        if (proto == null || proto.equals(Money.getDefaultInstance())) {
            return null;
        }
        Currency currency = Currency.getInstance(proto.getCurrency());
        // Convert units + nanos to BigDecimal
        BigDecimal amount =
                BigDecimal.valueOf(proto.getUnits()).add(BigDecimal.valueOf(proto.getNanos(), 9));
        return new TicketInfo.Money(currency, amount);
    }

    default Money toMoneyProto(TicketInfo.Money doc) {
        if (doc == null) return Money.getDefaultInstance();
        Money.Builder builder = Money.newBuilder();
        if (doc.currency() != null) {
            builder.setCurrency(doc.currency().getCurrencyCode());
        }
        if (doc.amount() != null) {
            // Convert BigDecimal to units + nanos
            long units = doc.amount().longValue();
            int nanos = doc.amount().remainder(BigDecimal.ONE).movePointRight(9).intValue();
            builder.setUnits(units);
            builder.setNanos(nanos);
        }
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
