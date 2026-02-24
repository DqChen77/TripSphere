package org.tripsphere.product.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.tripsphere.product.model.SpuDoc;

public interface CustomSpuDocRepository {

    /** Find SPUs by resource type and resource ID with pagination. */
    Page<SpuDoc> findByResource(String resourceType, String resourceId, Pageable pageable);

    /** Find a SPU that contains a specific SKU ID. */
    Optional<SpuDoc> findBySkuId(String skuId);

    /** Update specific fields of a SPU using field mask. */
    void updateSpuFields(String id, SpuDoc updates, java.util.List<String> fieldPaths);
}
