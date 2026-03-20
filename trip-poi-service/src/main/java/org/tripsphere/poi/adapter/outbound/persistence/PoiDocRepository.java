package org.tripsphere.poi.adapter.outbound.persistence;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.tripsphere.poi.adapter.outbound.persistence.document.PoiDoc;

public interface PoiDocRepository extends MongoRepository<PoiDoc, String>, CustomPoiDocRepository {

    Optional<PoiDoc> findByAmapId(String amapId);
}
