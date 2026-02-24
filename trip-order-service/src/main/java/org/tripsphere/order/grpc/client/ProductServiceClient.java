package org.tripsphere.order.grpc.client;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;
import org.tripsphere.product.v1.*;

/**
 * gRPC client for trip-product-service. Used by Order Service to validate SKUs and fetch product
 * snapshots.
 */
@Slf4j
@Component
public class ProductServiceClient {

    @GrpcClient("trip-product-service")
    private ProductServiceGrpc.ProductServiceBlockingStub productStub;

    /** Get a single SKU by ID. */
    public StockKeepingUnit getSkuById(String skuId) {
        log.debug("Fetching SKU: {}", skuId);
        GetSkuByIdResponse response =
                productStub.getSkuById(GetSkuByIdRequest.newBuilder().setId(skuId).build());
        return response.getSku();
    }

    /** Batch get SKUs by IDs. */
    public List<StockKeepingUnit> batchGetSkus(List<String> skuIds) {
        log.debug("Batch fetching {} SKUs", skuIds.size());
        BatchGetSkusResponse response =
                productStub.batchGetSkus(
                        BatchGetSkusRequest.newBuilder().addAllIds(skuIds).build());
        return response.getSkusList();
    }

    /** Get SPU by ID (for product name snapshot). */
    public StandardProductUnit getSpuById(String spuId) {
        log.debug("Fetching SPU: {}", spuId);
        GetSpuByIdResponse response =
                productStub.getSpuById(GetSpuByIdRequest.newBuilder().setId(spuId).build());
        return response.getSpu();
    }
}
