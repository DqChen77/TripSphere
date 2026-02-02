package org.tripsphere.attraction.service;

import java.util.List;

import org.tripsphere.attraction.model.AttractionDoc;

public interface AttractionService {
    boolean deleteAttraction(String id);

    AttractionDoc findAttractionById(String id);

    List<AttractionDoc> findAttractionsLocationNear(
            double longitude, double latitude, double radiusKm, List<String> tags);
}
