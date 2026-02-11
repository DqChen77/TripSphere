package org.tripsphere.attraction.repository;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.tripsphere.attraction.model.AttractionDoc;

public interface AttractionRepository
        extends MongoRepository<AttractionDoc, String>, CustomAttractionRepository {

    Optional<AttractionDoc> findByPoiId(String poiId);
}
