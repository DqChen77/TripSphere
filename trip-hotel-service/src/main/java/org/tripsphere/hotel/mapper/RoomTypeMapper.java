package org.tripsphere.hotel.mapper;

import java.util.List;
import org.mapstruct.*;
import org.mapstruct.factory.Mappers;
import org.tripsphere.hotel.model.RoomTypeDoc;
import org.tripsphere.hotel.v1.RoomType;

@Mapper(
        collectionMappingStrategy = CollectionMappingStrategy.ADDER_PREFERRED,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
public interface RoomTypeMapper {
    RoomTypeMapper INSTANCE = Mappers.getMapper(RoomTypeMapper.class);

    RoomTypeDoc toDoc(RoomType roomType);

    RoomType toProto(RoomTypeDoc roomTypeDoc);

    List<RoomType> toProtoList(List<RoomTypeDoc> roomTypeDocs);
}
