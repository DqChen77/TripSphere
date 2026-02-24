package org.tripsphere.product.api.grpc;

import io.grpc.stub.StreamObserver;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.data.domain.Page;
import org.tripsphere.product.exception.InvalidArgumentException;
import org.tripsphere.product.exception.NotFoundException;
import org.tripsphere.product.service.ProductService;
import org.tripsphere.product.v1.*;

@GrpcService
@RequiredArgsConstructor
public class ProductGrpcService extends ProductServiceGrpc.ProductServiceImplBase {

    private final ProductService productService;

    @Override
    public void createSpu(
            CreateSpuRequest request, StreamObserver<CreateSpuResponse> responseObserver) {
        if (!request.hasSpu()) {
            throw InvalidArgumentException.required("spu");
        }

        StandardProductUnit created = productService.createSpu(request.getSpu());

        responseObserver.onNext(CreateSpuResponse.newBuilder().setSpu(created).build());
        responseObserver.onCompleted();
    }

    @Override
    public void batchCreateSpus(
            BatchCreateSpusRequest request,
            StreamObserver<BatchCreateSpusResponse> responseObserver) {
        List<CreateSpuRequest> requests = request.getRequestsList();
        if (requests.isEmpty()) {
            throw new InvalidArgumentException("Request list for batch creation is empty");
        }

        List<StandardProductUnit> spusToCreate =
                requests.stream()
                        .filter(CreateSpuRequest::hasSpu)
                        .map(CreateSpuRequest::getSpu)
                        .toList();

        if (spusToCreate.isEmpty()) {
            throw new InvalidArgumentException("No valid SPU data in batch creation request");
        }

        List<StandardProductUnit> created = productService.batchCreateSpus(spusToCreate);

        responseObserver.onNext(BatchCreateSpusResponse.newBuilder().addAllSpus(created).build());
        responseObserver.onCompleted();
    }

    @Override
    public void getSpuById(
            GetSpuByIdRequest request, StreamObserver<GetSpuByIdResponse> responseObserver) {
        String id = request.getId();
        if (id.isEmpty()) {
            throw InvalidArgumentException.required("id");
        }

        StandardProductUnit spu =
                productService.getSpuById(id).orElseThrow(() -> new NotFoundException("SPU", id));

        responseObserver.onNext(GetSpuByIdResponse.newBuilder().setSpu(spu).build());
        responseObserver.onCompleted();
    }

    @Override
    public void batchGetSpus(
            BatchGetSpusRequest request, StreamObserver<BatchGetSpusResponse> responseObserver) {
        List<String> ids = request.getIdsList();
        if (ids.isEmpty()) {
            responseObserver.onNext(BatchGetSpusResponse.newBuilder().build());
            responseObserver.onCompleted();
            return;
        }

        List<StandardProductUnit> spus = productService.batchGetSpus(ids);

        responseObserver.onNext(BatchGetSpusResponse.newBuilder().addAllSpus(spus).build());
        responseObserver.onCompleted();
    }

    @Override
    public void listSpusByResource(
            ListSpusByResourceRequest request,
            StreamObserver<ListSpusByResourceResponse> responseObserver) {
        if (request.getResourceType() == ResourceType.RESOURCE_TYPE_UNSPECIFIED) {
            throw InvalidArgumentException.required("resource_type");
        }
        if (request.getResourceId().isEmpty()) {
            throw InvalidArgumentException.required("resource_id");
        }

        String resourceType =
                switch (request.getResourceType()) {
                    case RESOURCE_TYPE_HOTEL_ROOM -> "HOTEL_ROOM";
                    case RESOURCE_TYPE_ATTRACTION -> "ATTRACTION";
                    default -> "UNSPECIFIED";
                };

        Page<StandardProductUnit> page =
                productService.listSpusByResource(
                        resourceType,
                        request.getResourceId(),
                        request.getPageSize(),
                        request.getPageToken());

        ListSpusByResourceResponse.Builder responseBuilder =
                ListSpusByResourceResponse.newBuilder().addAllSpus(page.getContent());

        if (page.hasNext()) {
            responseBuilder.setNextPageToken(String.valueOf(page.getNumber() + 1));
        }

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void updateSpu(
            UpdateSpuRequest request, StreamObserver<UpdateSpuResponse> responseObserver) {
        if (!request.hasSpu()) {
            throw InvalidArgumentException.required("spu");
        }
        if (request.getSpu().getId().isEmpty()) {
            throw InvalidArgumentException.required("spu.id");
        }

        List<String> fieldPaths = List.of();
        if (request.hasFieldMask()) {
            fieldPaths = request.getFieldMask().getPathsList();
        }

        StandardProductUnit updated = productService.updateSpu(request.getSpu(), fieldPaths);

        responseObserver.onNext(UpdateSpuResponse.newBuilder().setSpu(updated).build());
        responseObserver.onCompleted();
    }

    @Override
    public void getSkuById(
            GetSkuByIdRequest request, StreamObserver<GetSkuByIdResponse> responseObserver) {
        String id = request.getId();
        if (id.isEmpty()) {
            throw InvalidArgumentException.required("id");
        }

        StockKeepingUnit sku =
                productService.getSkuById(id).orElseThrow(() -> new NotFoundException("SKU", id));

        responseObserver.onNext(GetSkuByIdResponse.newBuilder().setSku(sku).build());
        responseObserver.onCompleted();
    }

    @Override
    public void batchGetSkus(
            BatchGetSkusRequest request, StreamObserver<BatchGetSkusResponse> responseObserver) {
        List<String> ids = request.getIdsList();
        if (ids.isEmpty()) {
            responseObserver.onNext(BatchGetSkusResponse.newBuilder().build());
            responseObserver.onCompleted();
            return;
        }

        List<StockKeepingUnit> skus = productService.batchGetSkus(ids);

        responseObserver.onNext(BatchGetSkusResponse.newBuilder().addAllSkus(skus).build());
        responseObserver.onCompleted();
    }
}
