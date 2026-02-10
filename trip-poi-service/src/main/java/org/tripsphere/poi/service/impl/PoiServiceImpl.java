package org.tripsphere.poi.service.impl;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Point;
import org.springframework.stereotype.Service;
import org.tripsphere.poi.model.PoiDoc;
import org.tripsphere.poi.model.PoiSearchFilter;
import org.tripsphere.poi.repository.PoiRepository;
import org.tripsphere.poi.service.PoiService;

@Slf4j
@Service
@RequiredArgsConstructor
public class PoiServiceImpl implements PoiService {

    private final PoiRepository poiRepository;

    @Override
    public Optional<PoiDoc> findById(String id) {
        log.debug("Finding POI by id: {}", id);
        return poiRepository.findById(id);
    }

    @Override
    public Optional<PoiDoc> findByAmapId(String amapId) {
        log.debug("Finding POI by amapId: {}", amapId);
        return poiRepository.findByAmapId(amapId);
    }

    @Override
    public List<PoiDoc> findAllByIds(List<String> ids) {
        log.debug("Finding POIs by ids, count: {}", ids.size());
        return poiRepository.findAllById(ids);
    }

    @Override
    public List<PoiDoc> searchNearby(
            Point location, double radiusMeters, int limit, PoiSearchFilter filter) {
        log.debug(
                "Searching POIs nearby location: ({}, {}), radius: {}m, limit: {}",
                location.getX(),
                location.getY(),
                radiusMeters,
                limit);
        return poiRepository.findAllByLocationNear(location, radiusMeters, limit, filter);
    }

    @Override
    public List<PoiDoc> searchInBounds(
            Point southWest, Point northEast, int limit, PoiSearchFilter filter) {
        log.debug(
                "Searching POIs in bounds: SW({}, {}), NE({}, {}), limit: {}",
                southWest.getX(),
                southWest.getY(),
                northEast.getX(),
                northEast.getY(),
                limit);
        return poiRepository.findAllByLocationInBox(southWest, northEast, limit, filter);
    }

    @Override
    public PoiDoc createPoi(PoiDoc poiDoc) {
        // Server generates the ID, ignore any client-provided ID
        poiDoc.setId(null);
        log.debug("Creating new POI: {}", poiDoc.getName());
        PoiDoc saved = poiRepository.save(poiDoc);
        log.info("Created POI with id: {}", saved.getId());
        return saved;
    }

    @Override
    public List<PoiDoc> batchCreatePois(List<PoiDoc> poiDocs) {
        // Let server generates IDs for all POIs
        List<PoiDoc> toSave = poiDocs.stream().peek(poi -> poi.setId(null)).toList();

        log.debug("Batch creating {} POIs", toSave.size());
        List<PoiDoc> saved = poiRepository.saveAll(toSave);
        log.info("Batch created {} POIs", saved.size());
        return saved;
    }

    @Override
    public Optional<PoiDoc> updatePoi(String id, PoiDoc poiDoc) {
        log.debug("Updating POI with id: {}", id);
        return poiRepository
                .findById(id)
                .map(
                        existing -> {
                            // Preserve the original ID and timestamps
                            poiDoc.setId(existing.getId());
                            poiDoc.setCreatedAt(existing.getCreatedAt());
                            PoiDoc updated = poiRepository.save(poiDoc);
                            log.info("Updated POI with id: {}", id);
                            return updated;
                        });
    }

    @Override
    public boolean deletePoi(String id) {
        log.debug("Deleting POI with id: {}", id);
        if (poiRepository.existsById(id)) {
            poiRepository.deleteById(id);
            log.info("Deleted POI with id: {}", id);
            return true;
        }
        log.warn("POI not found for deletion, id: {}", id);
        return false;
    }
}
