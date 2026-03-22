package org.tripsphere.attraction.repository;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.tripsphere.attraction.model.AttractionDoc;

public interface AttractionDocRepository extends MongoRepository<AttractionDoc, String>, CustomAttractionDocRepository {

    Optional<AttractionDoc> findByPoiId(String poiId);
}
