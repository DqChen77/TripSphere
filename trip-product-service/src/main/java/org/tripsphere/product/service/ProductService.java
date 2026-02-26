package org.tripsphere.product.service;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.tripsphere.product.v1.StandardProductUnit;
import org.tripsphere.product.v1.StockKeepingUnit;

public interface ProductService {

    StandardProductUnit createSpu(StandardProductUnit spu);

    List<StandardProductUnit> batchCreateSpus(List<StandardProductUnit> spus);

    Optional<StandardProductUnit> getSpuById(String id);

    List<StandardProductUnit> batchGetSpus(List<String> ids);

    Page<StandardProductUnit> listSpusByResource(
            String resourceType, String resourceId, int pageSize, String pageToken);

    StandardProductUnit updateSpu(StandardProductUnit spu, List<String> fieldPaths);

    Optional<StockKeepingUnit> getSkuById(String skuId);

    List<StockKeepingUnit> batchGetSkus(List<String> skuIds);
}
