package org.tripsphere.poi.repository;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.tripsphere.poi.model.PoiDoc;

public interface PoiDocRepository extends MongoRepository<PoiDoc, String>, CustomPoiDocRepository {
    public Optional<PoiDoc> findByAmapId(String amapId);
}
