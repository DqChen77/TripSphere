package org.tripsphere.poi.repository;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.tripsphere.poi.model.PoiDoc;

public interface PoiRepository extends MongoRepository<PoiDoc, String>, CustomPoiRepository {
    public Optional<PoiDoc> findByAmapId(String amapId);
}
