package org.tripsphere.attraction.service.impl;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.tripsphere.attraction.model.AttractionDoc;
import org.tripsphere.attraction.repository.AttractionRepository;
import org.tripsphere.attraction.service.AttractionService;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class AttractionServiceImpl implements AttractionService {
    private final AttractionRepository attractionRepository;

    public AttractionServiceImpl(AttractionRepository attractionRepository) {
        this.attractionRepository = attractionRepository;
    }

    /**
     * Delete attraction
     *
     * @param id Attraction id
     * @return If delete success, return true, else return false
     */
    @Override
    public boolean deleteAttraction(String id) {
        Optional<AttractionDoc> attractionOptional = attractionRepository.findById(id);
        if (attractionOptional.isPresent()) {
            AttractionDoc attraction = attractionOptional.get();
            attractionRepository.delete(attraction);
        } else return false;
        return true;
    }

    /**
     * Find attraction by id
     *
     * @param id Attraction id
     * @return If found, return attraction, else return null
     */
    @Override
    public AttractionDoc findAttractionById(String id) {
        Optional<AttractionDoc> attractionOptional = attractionRepository.findById(id);
        return attractionOptional.orElse(null);
    }

    /**
     * Find attractions located at the specified location and within the specified distance range,
     * and list them in order from near to far.
     *
     * @param longitude Longitude
     * @param latitude Latitude
     * @param radiusKm Distance to center(km)
     * @return The list of attractions
     */
    @Override
    public List<AttractionDoc> findAttractionsLocationNear(
            double longitude, double latitude, double radiusKm, List<String> tags) {
        double maxDistanceMeters = radiusKm * 1000; // MongoDB expects meters for $nearSphere
        return attractionRepository.findByLocationNear(longitude, latitude, maxDistanceMeters);
    }
}
