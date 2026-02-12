package org.tripsphere.itinerary.mapper;

import com.github.f4b6a3.uuid.UuidCreator;
import com.google.protobuf.Struct;
import com.google.protobuf.util.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mapstruct.CollectionMappingStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import org.tripsphere.common.v1.Date;
import org.tripsphere.common.v1.Money;
import org.tripsphere.common.v1.TimeOfDay;
import org.tripsphere.itinerary.model.ActivityDoc;
import org.tripsphere.itinerary.model.ActivityKind;
import org.tripsphere.itinerary.model.DayPlanDoc;
import org.tripsphere.itinerary.model.ItineraryDoc;
import org.tripsphere.itinerary.v1.Activity;
import org.tripsphere.itinerary.v1.DayPlan;
import org.tripsphere.itinerary.v1.Itinerary;

/**
 * MapStruct mapper for converting between Proto messages and MongoDB documents.
 *
 * <p>Note: External resources (Poi, Attraction, Hotel) are handled separately - only IDs are stored
 * in documents, and full objects are populated at service layer via gRPC calls.
 */
@Mapper(
        collectionMappingStrategy = CollectionMappingStrategy.ADDER_PREFERRED,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
public interface ItineraryMapper {
    ItineraryMapper INSTANCE = Mappers.getMapper(ItineraryMapper.class);

    // ==================== Itinerary Mappings ====================

    /**
     * Convert Itinerary proto to ItineraryDoc. Note: destination POI is converted to just an ID
     * reference.
     *
     * <p>Internal fields (archived, createdAt, updatedAt) are ignored to prevent accidental
     * overwrites during updates. These should be managed by the service layer or Spring Data.
     */
    @Mapping(target = "destinationPoiId", source = "destination.id")
    @Mapping(target = "startDate", source = "startDate", qualifiedByName = "toLocalDate")
    @Mapping(target = "endDate", source = "endDate", qualifiedByName = "toLocalDate")
    @Mapping(target = "metadata", source = "metadata", qualifiedByName = "structToMap")
    @Mapping(target = "archived", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    ItineraryDoc toDoc(Itinerary itinerary);

    /**
     * Convert ItineraryDoc to Itinerary proto. Note: destination is NOT populated here - must be
     * done at service layer.
     */
    @Mapping(target = "destination", ignore = true)
    @Mapping(target = "startDate", source = "startDate", qualifiedByName = "toDateProto")
    @Mapping(target = "endDate", source = "endDate", qualifiedByName = "toDateProto")
    @Mapping(target = "metadata", source = "metadata", qualifiedByName = "mapToStruct")
    Itinerary toProto(ItineraryDoc doc);

    List<Itinerary> toProtoList(List<ItineraryDoc> docs);

    // ==================== DayPlan Mappings ====================

    @Mapping(target = "id", source = "id", qualifiedByName = "ensureId")
    @Mapping(target = "date", source = "date", qualifiedByName = "toLocalDate")
    @Mapping(target = "metadata", source = "metadata", qualifiedByName = "structToMap")
    DayPlanDoc toDayPlanDoc(DayPlan dayPlan);

    @Mapping(target = "date", source = "date", qualifiedByName = "toDateProto")
    @Mapping(target = "metadata", source = "metadata", qualifiedByName = "mapToStruct")
    DayPlan toDayPlanProto(DayPlanDoc doc);

    List<DayPlanDoc> toDayPlanDocList(List<DayPlan> dayPlans);

    List<DayPlan> toDayPlanProtoList(List<DayPlanDoc> docs);

    // ==================== Activity Mappings ====================

    @Mapping(target = "id", source = "id", qualifiedByName = "ensureId")
    @Mapping(target = "kind", source = "kind", qualifiedByName = "protoKindToDocKind")
    @Mapping(target = "startTime", source = "startTime", qualifiedByName = "toLocalTime")
    @Mapping(target = "endTime", source = "endTime", qualifiedByName = "toLocalTime")
    @Mapping(target = "estimatedCost", source = "estimatedCost", qualifiedByName = "toMoney")
    @Mapping(target = "attractionId", source = "attraction.id")
    @Mapping(target = "hotelId", source = "hotel.id")
    @Mapping(target = "metadata", source = "metadata", qualifiedByName = "structToMap")
    ActivityDoc toActivityDoc(Activity activity);

    /**
     * Convert ActivityDoc to Activity proto. Note: attraction and hotel are NOT populated here -
     * must be done at service layer.
     */
    @Mapping(target = "kind", source = "kind", qualifiedByName = "docKindToProtoKind")
    @Mapping(target = "startTime", source = "startTime", qualifiedByName = "toTimeOfDayProto")
    @Mapping(target = "endTime", source = "endTime", qualifiedByName = "toTimeOfDayProto")
    @Mapping(target = "estimatedCost", source = "estimatedCost", qualifiedByName = "toMoneyProto")
    @Mapping(target = "attraction", ignore = true)
    @Mapping(target = "hotel", ignore = true)
    @Mapping(target = "metadata", source = "metadata", qualifiedByName = "mapToStruct")
    Activity toActivityProto(ActivityDoc doc);

    List<ActivityDoc> toActivityDocList(List<Activity> activities);

    List<Activity> toActivityProtoList(List<ActivityDoc> docs);

    // ==================== Date Mappings (LocalDate <-> proto Date) ====================

    @Named("toLocalDate")
    default LocalDate toLocalDate(Date date) {
        if (date == null || date.equals(Date.getDefaultInstance())) {
            return null;
        }
        return LocalDate.of(date.getYear(), date.getMonth(), date.getDay());
    }

    @Named("toDateProto")
    default Date toDateProto(LocalDate localDate) {
        if (localDate == null) return Date.getDefaultInstance();
        return Date.newBuilder()
                .setYear(localDate.getYear())
                .setMonth(localDate.getMonthValue())
                .setDay(localDate.getDayOfMonth())
                .build();
    }

    // ==================== TimeOfDay Mappings (LocalTime <-> proto TimeOfDay) ====================

    @Named("toLocalTime")
    default LocalTime toLocalTime(TimeOfDay timeOfDay) {
        if (timeOfDay == null || timeOfDay.equals(TimeOfDay.getDefaultInstance())) {
            return null;
        }
        return LocalTime.of(
                timeOfDay.getHours(),
                timeOfDay.getMinutes(),
                timeOfDay.getSeconds(),
                timeOfDay.getNanos());
    }

    @Named("toTimeOfDayProto")
    default TimeOfDay toTimeOfDayProto(LocalTime localTime) {
        if (localTime == null) return TimeOfDay.getDefaultInstance();
        return TimeOfDay.newBuilder()
                .setHours(localTime.getHour())
                .setMinutes(localTime.getMinute())
                .setSeconds(localTime.getSecond())
                .setNanos(localTime.getNano())
                .build();
    }

    // ==================== Money Mappings (ActivityDoc.Money <-> proto Money) ====================

    @Named("toMoney")
    default ActivityDoc.Money toMoney(Money proto) {
        if (proto == null || proto.equals(Money.getDefaultInstance())) {
            return null;
        }
        Currency currency = Currency.getInstance(proto.getCurrency());
        // Convert units + nanos to BigDecimal
        BigDecimal amount =
                BigDecimal.valueOf(proto.getUnits()).add(BigDecimal.valueOf(proto.getNanos(), 9));
        return new ActivityDoc.Money(currency, amount);
    }

    @Named("toMoneyProto")
    default Money toMoneyProto(ActivityDoc.Money doc) {
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

    // ==================== Custom Converters ====================

    @Named("ensureId")
    default String ensureId(String id) {
        return (id == null || id.isEmpty()) ? UuidCreator.getTimeOrderedEpoch().toString() : id;
    }

    @Named("protoKindToDocKind")
    default ActivityKind protoKindToDocKind(org.tripsphere.itinerary.v1.ActivityKind protoKind) {
        if (protoKind == null) {
            return ActivityKind.UNSPECIFIED;
        }
        return switch (protoKind) {
            case ACTIVITY_KIND_ATTRACTION_VISIT -> ActivityKind.ATTRACTION_VISIT;
            case ACTIVITY_KIND_DINING -> ActivityKind.DINING;
            case ACTIVITY_KIND_HOTEL_STAY -> ActivityKind.HOTEL_STAY;
            case ACTIVITY_KIND_CUSTOM -> ActivityKind.CUSTOM;
            default -> ActivityKind.UNSPECIFIED;
        };
    }

    @Named("docKindToProtoKind")
    default org.tripsphere.itinerary.v1.ActivityKind docKindToProtoKind(ActivityKind docKind) {
        if (docKind == null) {
            return org.tripsphere.itinerary.v1.ActivityKind.ACTIVITY_KIND_UNSPECIFIED;
        }
        return switch (docKind) {
            case ATTRACTION_VISIT -> org.tripsphere.itinerary.v1.ActivityKind
                    .ACTIVITY_KIND_ATTRACTION_VISIT;
            case DINING -> org.tripsphere.itinerary.v1.ActivityKind.ACTIVITY_KIND_DINING;
            case HOTEL_STAY -> org.tripsphere.itinerary.v1.ActivityKind.ACTIVITY_KIND_HOTEL_STAY;
            case CUSTOM -> org.tripsphere.itinerary.v1.ActivityKind.ACTIVITY_KIND_CUSTOM;
            default -> org.tripsphere.itinerary.v1.ActivityKind.ACTIVITY_KIND_UNSPECIFIED;
        };
    }

    @Named("structToMap")
    default Map<String, Object> structToMap(Struct struct) {
        if (struct == null || struct.getFieldsCount() == 0) {
            return null;
        }
        try {
            String json = JsonFormat.printer().print(struct);
            // Simple JSON to Map conversion - in production, use a proper JSON library
            @SuppressWarnings("unchecked")
            Map<String, Object> map =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(json, HashMap.class);
            return map;
        } catch (Exception e) {
            return null;
        }
    }

    @Named("mapToStruct")
    default Struct mapToStruct(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return Struct.getDefaultInstance();
        }
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
            Struct.Builder builder = Struct.newBuilder();
            JsonFormat.parser().merge(json, builder);
            return builder.build();
        } catch (Exception e) {
            return Struct.getDefaultInstance();
        }
    }
}
