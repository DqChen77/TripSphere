package org.tripsphere.itinerary.mapper;

import java.util.List;
import org.mapstruct.CollectionMappingStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import org.tripsphere.itinerary.model.DayPlanDoc;
import org.tripsphere.itinerary.v1.DayPlan;

@Mapper(
        collectionMappingStrategy = CollectionMappingStrategy.ADDER_PREFERRED,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
        uses = {CommonMapper.class, ActivityMapper.class})
public interface DayPlanMapper {
    DayPlanMapper INSTANCE = Mappers.getMapper(DayPlanMapper.class);

    // ===================================================================
    // DayPlan Mappings
    // ===================================================================

    DayPlanDoc toDoc(DayPlan dayPlan);

    DayPlan toProto(DayPlanDoc doc);

    List<DayPlanDoc> toDayPlanDocList(List<DayPlan> dayPlans);

    List<DayPlan> toDayPlanProtoList(List<DayPlanDoc> docs);
}
