package org.tripsphere.attraction.service;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.tripsphere.attraction.model.AttractionEntity;
import org.tripsphere.attraction.repository.AttractionRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class AttractionService {
    private final AttractionRepository attractionRepository;

    public AttractionService(AttractionRepository attractionRepository) {
        this.attractionRepository = attractionRepository;
    }

    /**
     * Add attraction
     *
     * @param attraction attraction
     * @return the attraction id
     */
    public String addAttraction(AttractionEntity attraction) {
        attractionRepository.save(attraction);
        return attraction.getId();
    }

    /**
     * Delete attraction
     *
     * @param id attraction id
     * @return if delete success, return true, else return false
     */
    public boolean deleteAttraction(String id) {
        Optional<AttractionEntity> attractionOptional = attractionRepository.findById(id);
        if (attractionOptional.isPresent()) {
            AttractionEntity attraction = attractionOptional.get();
            attractionRepository.delete(attraction);
        } else return false;
        return true;
    }

    /**
     * Change attraction information
     *
     * @param attraction attraction
     * @return if change success, return true, else return false
     */
    public boolean changAttraction(AttractionEntity attraction) {
        Optional<AttractionEntity> attractionOptional =
                attractionRepository.findById(attraction.getId());
        if (!attractionOptional.isPresent()) return false;
        AttractionEntity attractionOld = attractionOptional.get();
        attractionOld.setName(attraction.getName());
        attractionOld.setAddress(attraction.getAddress());
        attractionOld.setIntroduction(attraction.getIntroduction());
        attractionOld.setLocation(attraction.getLocation());
        attractionOld.setTags(attraction.getTags());
        attractionOld.setImages(attraction.getImages());
        attractionRepository.save(attractionOld);
        return true;
    }

    /**
     * find attraction by id
     *
     * @param id attraction id
     * @return if found, return attraction, else return null
     */
    public AttractionEntity findAttractionById(String id) {
        Optional<AttractionEntity> attractionOptional = attractionRepository.findById(id);
        if (attractionOptional.isPresent()) {
            AttractionEntity attraction = attractionOptional.get();
            return attraction;
        } else return null;
    }

    /**
     * Find attractions located at the specified location and within the specified distance range,
     * and list them in order from near to far.
     *
     * @param lng Longitude
     * @param lat Latitude
     * @param radiusKm distance to center(km)
     * @return The list of attractions
     */
    public List<AttractionEntity> findAttractionsWithinRadius(
            double lng, double lat, double radiusKm, String name, List<String> tags) {
        double maxDistanceMeters = radiusKm * 1000; // Mongo expects meters for $nearSphere
        String nameRegex = (name == null || name.isBlank()) ? ".*" : ".*" + name + ".*";
        return attractionRepository.findByLocationNear(lng, lat, maxDistanceMeters);
    }

    /**
     * Find attractions located at the specified location and within the specified distance range
     *
     * @param lng Longitude
     * @param lat Latitude
     * @param radiusKm distance to center(km)
     * @return The list of attractions
     */
    public List<AttractionEntity> findAttractionsWithinCircle(
            double lng, double lat, double radiusKm, String name, List<String> tags) {
        double radiusInRadians = radiusKm / 6378.1; // convert km -> radians for $centerSphere
        String nameRegex = (name == null || name.isBlank()) ? ".*" : ".*" + name + ".*";
        return attractionRepository.findByLocationWithinWithFilters(
                lng, lat, radiusInRadians, nameRegex, tags);
    }

    /**
     * Find attractions located at the specified location and within the specified distance range,
     * and list them in order from near to far, and result has been paginated
     *
     * @param lng Longitude
     * @param lat Latitude
     * @param radiusKm distance to center(km)
     * @param page which page you want to find, start from 0
     * @param size page size
     * @return The page of attractions
     */
    public Page<AttractionEntity> findAttractionsWithinRadius(
            double lng,
            double lat,
            double radiusKm,
            String name,
            List<String> tags,
            int page,
            int size) {
        double maxDistanceMeters = radiusKm * 1000;
        String nameRegex = (name == null || name.isBlank()) ? ".*" : ".*" + name + ".*";
        Pageable pageable = PageRequest.of(page, size);
        return attractionRepository.findByLocationNearWithFilters(
                lng, lat, maxDistanceMeters, nameRegex, tags, pageable);
    }

    /**
     * Find attractions located at the specified location and within the specified distance range,
     * and result has been paginated
     *
     * @param lng Longitude
     * @param lat Latitude
     * @param radiusKm distance to center(km)
     * @param page which page you want to find, start from 0
     * @param size page size
     * @return The page of attractions
     */
    public Page<AttractionEntity> findAttractionsWithinCircle(
            double lng,
            double lat,
            double radiusKm,
            String name,
            List<String> tags,
            int page,
            int size) {
        double radiusInRadians = radiusKm / 6378.1;
        String nameRegex = (name == null || name.isBlank()) ? ".*" : ".*" + name + ".*";
        Pageable pageable = PageRequest.of(page, size);
        return attractionRepository.findByLocationWithinWithFilters(
                lng, lat, radiusInRadians, nameRegex, tags, pageable);
    }
}
