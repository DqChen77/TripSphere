package org.tripsphere.attraction.mapper;

import java.util.List;
import org.mapstruct.*;
import org.mapstruct.factory.Mappers;
import org.tripsphere.attraction.model.AttractionDoc;
import org.tripsphere.attraction.v1.Attraction;

@Mapper(
        collectionMappingStrategy = CollectionMappingStrategy.ADDER_PREFERRED,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
        uses = {CommonMapper.class, MoneyMapper.class})
public interface AttractionMapper {
    AttractionMapper INSTANCE = Mappers.getMapper(AttractionMapper.class);

    // ===================================================================
    // Attraction Mappings
    // ===================================================================

    AttractionDoc toDoc(Attraction attraction);

    Attraction toProto(AttractionDoc attractionDoc);

    List<Attraction> toProtoList(List<AttractionDoc> attractionDocs);
}
