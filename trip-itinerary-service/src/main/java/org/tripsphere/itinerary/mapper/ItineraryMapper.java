package org.tripsphere.itinerary.mapper;

import java.util.List;
import org.mapstruct.CollectionMappingStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import org.tripsphere.itinerary.model.ItineraryDoc;
import org.tripsphere.itinerary.v1.Itinerary;

@Mapper(
        collectionMappingStrategy = CollectionMappingStrategy.ADDER_PREFERRED,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
        uses = {CommonMapper.class, ActivityMapper.class})
public interface ItineraryMapper {
    ItineraryMapper INSTANCE = Mappers.getMapper(ItineraryMapper.class);

    // ===================================================================
    // Itinerary Mappings
    // ===================================================================

    /**
     * Convert Itinerary proto to ItineraryDoc. Note: destination POI is converted to just an ID
     * reference.
     *
     * <p>Internal fields (archived, createdAt, updatedAt) are ignored to prevent accidental
     * overwrites during updates. These should be managed by the service layer or Spring Data.
     */
    @Mapping(target = "destinationPoiId", source = "destination.id")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    ItineraryDoc toDoc(Itinerary itinerary);

    /**
     * Convert ItineraryDoc to Itinerary proto. Note: destination is NOT populated here - must be
     * done at service layer.
     */
    @Mapping(target = "destination", ignore = true)
    Itinerary toProto(ItineraryDoc doc);

    List<Itinerary> toProtoList(List<ItineraryDoc> docs);
}
