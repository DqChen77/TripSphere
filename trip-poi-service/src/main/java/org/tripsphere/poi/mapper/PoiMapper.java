package org.tripsphere.poi.mapper;

import java.util.List;
import org.mapstruct.*;
import org.mapstruct.factory.Mappers;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.tripsphere.common.v1.GeoPoint;
import org.tripsphere.poi.model.PoiDoc;
import org.tripsphere.poi.util.CoordinateTransformUtil;
import org.tripsphere.poi.v1.Poi;

@Mapper(
        collectionMappingStrategy = CollectionMappingStrategy.ADDER_PREFERRED,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
public interface PoiMapper {
    PoiMapper INSTANCE = Mappers.getMapper(PoiMapper.class);

    // ===================================================================
    // Poi Mappings
    // ===================================================================

    PoiDoc toDoc(Poi poi);

    Poi toProto(PoiDoc poiDoc);

    List<Poi> toProtoList(List<PoiDoc> poiDocs);

    default GeoJsonPoint toGeoJsonPoint(GeoPoint point) {
        if (point == null) return null;
        double[] coordinate =
                CoordinateTransformUtil.gcj02ToWgs84(point.getLongitude(), point.getLatitude());
        return new GeoJsonPoint(coordinate[0], coordinate[1]);
    }

    default GeoPoint toGeoPoint(GeoJsonPoint geoJsonPoint) {
        if (geoJsonPoint == null) return null;
        double[] coordinate =
                CoordinateTransformUtil.wgs84ToGcj02(geoJsonPoint.getX(), geoJsonPoint.getY());
        return GeoPoint.newBuilder().setLongitude(coordinate[0]).setLatitude(coordinate[1]).build();
    }
}
