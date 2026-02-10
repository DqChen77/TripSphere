package org.tripsphere.poi.repository.impl;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.geo.Box;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;
import org.tripsphere.poi.model.PoiDoc;
import org.tripsphere.poi.model.PoiSearchFilter;
import org.tripsphere.poi.repository.CustomPoiRepository;

@Repository
@RequiredArgsConstructor
public class CustomPoiRepositoryImpl implements CustomPoiRepository {
    private final MongoTemplate mongoTemplate;

    @Override
    public List<PoiDoc> findAllByLocationNear(
            Point location, double radiusMeters, int limit, PoiSearchFilter searchFilter) {
        Criteria criteria =
                Criteria.where("location").nearSphere(location).maxDistance(radiusMeters);
        if (searchFilter != null) {
            applyFilter(criteria, searchFilter);
        }

        Query query = new Query(criteria).with(PageRequest.of(0, limit));

        return mongoTemplate.find(query, PoiDoc.class);
    }

    @Override
    public List<PoiDoc> findAllByLocationInBox(
            Point southWest, Point northEast, int limit, PoiSearchFilter searchFilter) {
        Criteria criteria = Criteria.where("location").within(new Box(southWest, northEast));
        if (searchFilter != null) {
            applyFilter(criteria, searchFilter);
        }

        Query query = new Query(criteria).with(PageRequest.of(0, limit));

        return mongoTemplate.find(query, PoiDoc.class);
    }

    private void applyFilter(Criteria criteria, @NotNull PoiSearchFilter searchFilter) {
        if (searchFilter.getCategories() != null && !searchFilter.getCategories().isEmpty()) {
            criteria.and("categories").in(searchFilter.getCategories());
        }
        if (searchFilter.getAdcode() != null && !searchFilter.getAdcode().isEmpty()) {
            criteria.and("adcode").is(searchFilter.getAdcode());
        }
    }
}
