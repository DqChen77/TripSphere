package org.tripsphere.product.service;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.tripsphere.product.v1.StandardProductUnit;
import org.tripsphere.product.v1.StockKeepingUnit;

public interface ProductService {

    /** Create a new SPU (with optional embedded SKUs). */
    StandardProductUnit createSpu(StandardProductUnit spu);

    /** Batch create SPUs. */
    List<StandardProductUnit> batchCreateSpus(List<StandardProductUnit> spus);

    /** Get SPU by ID with its embedded SKUs. */
    Optional<StandardProductUnit> getSpuById(String id);

    /** Batch get SPUs by IDs. */
    List<StandardProductUnit> batchGetSpus(List<String> ids);

    /** List SPUs by resource type and resource ID with pagination. */
    Page<StandardProductUnit> listSpusByResource(
            String resourceType, String resourceId, int pageSize, String pageToken);

    /** Update SPU fields using a field mask. */
    StandardProductUnit updateSpu(StandardProductUnit spu, List<String> fieldPaths);

    /** Get a single SKU by ID (searches across all SPUs). */
    Optional<StockKeepingUnit> getSkuById(String skuId);

    /** Batch get SKUs by IDs. */
    List<StockKeepingUnit> batchGetSkus(List<String> skuIds);
}
