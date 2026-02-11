package org.tripsphere.attraction.service.impl;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Point;
import org.springframework.stereotype.Service;
import org.tripsphere.attraction.mapper.AttractionMapper;
import org.tripsphere.attraction.model.AttractionDoc;
import org.tripsphere.attraction.repository.AttractionRepository;
import org.tripsphere.attraction.service.AttractionService;
import org.tripsphere.attraction.util.CoordinateTransformUtil;
import org.tripsphere.attraction.v1.Attraction;
import org.tripsphere.common.v1.GeoPoint;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttractionServiceImpl implements AttractionService {

    private final AttractionRepository attractionRepository;
    private final AttractionMapper attractionMapper = AttractionMapper.INSTANCE;

    private static final int DEFAULT_NEARBY_LIMIT = 100;

    @Override
    public Optional<Attraction> findById(String id) {
        log.debug("Finding attraction by id: {}", id);
        return attractionRepository.findById(id).map(attractionMapper::toProto);
    }

    @Override
    public List<Attraction> findAllByIds(List<String> ids) {
        log.debug("Finding attractions by ids, count: {}", ids.size());
        List<AttractionDoc> docs = attractionRepository.findAllById(ids);
        return attractionMapper.toProtoList(docs);
    }

    @Override
    public List<Attraction> searchNearby(
            GeoPoint location, double radiusMeters, List<String> tags) {
        log.debug(
                "Searching attractions nearby location: ({}, {}), radius: {}m",
                location.getLongitude(),
                location.getLatitude(),
                radiusMeters);

        // Convert GCJ-02 (from client) to WGS84 (for MongoDB)
        Point wgs84Location = toWgs84Point(location);

        List<AttractionDoc> docs =
                attractionRepository.findAllByLocationNear(
                        wgs84Location, radiusMeters, DEFAULT_NEARBY_LIMIT, tags);
        return attractionMapper.toProtoList(docs);
    }

    @Override
    public Optional<Attraction> findByPoiId(String poiId) {
        log.debug("Finding attraction by poiId: {}", poiId);
        return attractionRepository.findByPoiId(poiId).map(attractionMapper::toProto);
    }

    /** Convert GeoPoint (GCJ-02) to Spring Point (WGS84) for MongoDB queries. */
    private Point toWgs84Point(GeoPoint geoPoint) {
        double[] wgs84 =
                CoordinateTransformUtil.gcj02ToWgs84(
                        geoPoint.getLongitude(), geoPoint.getLatitude());
        return new Point(wgs84[0], wgs84[1]);
    }
}
