package org.tripsphere.product.repository;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.tripsphere.product.model.SpuDoc;

public interface SpuDocRepository extends MongoRepository<SpuDoc, String>, CustomSpuDocRepository {

    List<SpuDoc> findByResourceTypeAndResourceId(String resourceType, String resourceId);
}
