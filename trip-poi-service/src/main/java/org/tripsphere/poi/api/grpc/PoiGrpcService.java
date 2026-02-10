package org.tripsphere.poi.api.grpc;

import com.google.protobuf.FieldMask;
import com.google.protobuf.util.FieldMaskUtil;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.data.geo.Point;
import org.tripsphere.common.v1.GeoPoint;
import org.tripsphere.poi.exception.InvalidArgumentException;
import org.tripsphere.poi.exception.NotFoundException;
import org.tripsphere.poi.mapper.PoiMapper;
import org.tripsphere.poi.model.PoiDoc;
import org.tripsphere.poi.model.PoiSearchFilter;
import org.tripsphere.poi.service.PoiService;
import org.tripsphere.poi.util.CoordinateTransformUtil;
import org.tripsphere.poi.v1.BatchCreatePoisRequest;
import org.tripsphere.poi.v1.BatchCreatePoisResponse;
import org.tripsphere.poi.v1.BatchGetPoisRequest;
import org.tripsphere.poi.v1.BatchGetPoisResponse;
import org.tripsphere.poi.v1.CreatePoiRequest;
import org.tripsphere.poi.v1.CreatePoiResponse;
import org.tripsphere.poi.v1.CreatePoiResult;
import org.tripsphere.poi.v1.GetPoiByIdRequest;
import org.tripsphere.poi.v1.GetPoiByIdResponse;
import org.tripsphere.poi.v1.Poi;
import org.tripsphere.poi.v1.PoiFilter;
import org.tripsphere.poi.v1.PoiServiceGrpc.PoiServiceImplBase;
import org.tripsphere.poi.v1.SearchPoisInBoundsRequest;
import org.tripsphere.poi.v1.SearchPoisInBoundsResponse;
import org.tripsphere.poi.v1.SearchPoisNearbyRequest;
import org.tripsphere.poi.v1.SearchPoisNearbyResponse;

/**
 * gRPC service implementation for POI operations. Exception handling is delegated to {@link
 * advice.GrpcExceptionAdvice}.
 */
@GrpcService
@RequiredArgsConstructor
public class PoiGrpcService extends PoiServiceImplBase {

    private final PoiService poiService;
    private final PoiMapper poiMapper = PoiMapper.INSTANCE;

    private static final int DEFAULT_SEARCH_LIMIT = 50;
    private static final int MAX_SEARCH_LIMIT = 200;

    @Override
    public void getPoiById(
            GetPoiByIdRequest request, StreamObserver<GetPoiByIdResponse> responseObserver) {
        String id = request.getId();
        if (id.isEmpty()) {
            throw InvalidArgumentException.required("POI ID");
        }

        PoiDoc poiDoc = poiService.findById(id).orElseThrow(() -> new NotFoundException("POI", id));

        Poi poiProto = poiMapper.toProto(poiDoc);
        GetPoiByIdResponse response = GetPoiByIdResponse.newBuilder().setPoi(poiProto).build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void batchGetPois(
            BatchGetPoisRequest request, StreamObserver<BatchGetPoisResponse> responseObserver) {
        List<String> ids = request.getIdsList();
        if (ids.isEmpty()) {
            // Return an empty map for empty request
            BatchGetPoisResponse response =
                    BatchGetPoisResponse.newBuilder().putAllPois(Map.of()).build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            return;
        }

        List<PoiDoc> poiDocs = poiService.findAllByIds(ids);
        FieldMask fieldMask = request.getFieldMask();
        boolean shouldTrim = request.hasFieldMask() && (fieldMask.getPathsCount() > 0);

        BatchGetPoisResponse.Builder responseBuilder = BatchGetPoisResponse.newBuilder();
        poiMapper
                .toProtoList(poiDocs)
                .forEach(
                        poiProto ->
                                responseBuilder.putPois(
                                        poiProto.getId(),
                                        shouldTrim
                                                ? FieldMaskUtil.trim(fieldMask, poiProto)
                                                : poiProto));

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void searchPoisNearby(
            SearchPoisNearbyRequest request,
            StreamObserver<SearchPoisNearbyResponse> responseObserver) {
        if (!request.hasLocation()) {
            throw InvalidArgumentException.required("location");
        }

        // Convert GCJ-02 coordinates (from client) to WGS84 (for MongoDB query)
        GeoPoint geoPoint = request.getLocation();
        double[] wgs84 =
                CoordinateTransformUtil.gcj02ToWgs84(
                        geoPoint.getLongitude(), geoPoint.getLatitude());
        Point location = new Point(wgs84[0], wgs84[1]);

        double radiusMeters = request.getRadiusMeters() > 0 ? request.getRadiusMeters() : 1000;
        int limit = normalizeLimit(request.getLimit());
        PoiSearchFilter filter = buildSearchFilter(request.getFilter());

        List<PoiDoc> poiDocs = poiService.searchNearby(location, radiusMeters, limit, filter);
        List<Poi> poiProtos = poiMapper.toProtoList(poiDocs);

        SearchPoisNearbyResponse response =
                SearchPoisNearbyResponse.newBuilder()
                        .addAllPois(poiProtos)
                        .setTotalCount(poiProtos.size())
                        .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void searchPoisInBounds(
            SearchPoisInBoundsRequest request,
            StreamObserver<SearchPoisInBoundsResponse> responseObserver) {
        if (!request.hasSouthWest() || !request.hasNorthEast()) {
            throw new InvalidArgumentException("Both southWest and northEast bounds are required");
        }

        // Convert GCJ-02 coordinates (from client) to WGS84 (for MongoDB query)
        GeoPoint sw = request.getSouthWest();
        GeoPoint ne = request.getNorthEast();
        double[] swWgs84 =
                CoordinateTransformUtil.gcj02ToWgs84(sw.getLongitude(), sw.getLatitude());
        double[] neWgs84 =
                CoordinateTransformUtil.gcj02ToWgs84(ne.getLongitude(), ne.getLatitude());

        Point southWest = new Point(swWgs84[0], swWgs84[1]);
        Point northEast = new Point(neWgs84[0], neWgs84[1]);
        int limit = normalizeLimit(request.getLimit());
        PoiSearchFilter filter = buildSearchFilter(request.getFilter());

        List<PoiDoc> poiDocs = poiService.searchInBounds(southWest, northEast, limit, filter);
        List<Poi> poiProtos = poiMapper.toProtoList(poiDocs);

        SearchPoisInBoundsResponse response =
                SearchPoisInBoundsResponse.newBuilder()
                        .addAllPois(poiProtos)
                        .setTotalCount(poiProtos.size())
                        .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void createPoi(
            CreatePoiRequest request, StreamObserver<CreatePoiResponse> responseObserver) {
        if (!request.hasPoi()) {
            throw InvalidArgumentException.required("poi");
        }

        PoiDoc poiDoc = poiMapper.toDoc(request.getPoi());
        PoiDoc savedPoiDoc = poiService.createPoi(poiDoc);
        Poi savedPoiProto = poiMapper.toProto(savedPoiDoc);

        CreatePoiResponse response = CreatePoiResponse.newBuilder().setPoi(savedPoiProto).build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void batchCreatePois(
            BatchCreatePoisRequest request,
            StreamObserver<BatchCreatePoisResponse> responseObserver) {
        List<Poi> pois = request.getPoisList();
        if (pois.isEmpty()) {
            throw new InvalidArgumentException("POI list for batch creation is empty");
        }

        List<PoiDoc> poiDocs = pois.stream().map(poiMapper::toDoc).toList();
        List<PoiDoc> savedDocs = poiService.batchCreatePois(poiDocs);

        // Build results for each POI
        List<CreatePoiResult> results = new ArrayList<>();
        for (int i = 0; i < savedDocs.size(); i++) {
            PoiDoc savedDoc = savedDocs.get(i);
            results.add(
                    CreatePoiResult.newBuilder()
                            .setIndex(i)
                            .setCode(CreatePoiResult.Code.CODE_SUCCESS)
                            .setPoiId(savedDoc.getId())
                            .build());
        }

        BatchCreatePoisResponse response =
                BatchCreatePoisResponse.newBuilder()
                        .setTotalCount(pois.size())
                        .setSuccessCount(savedDocs.size())
                        .setFailureCount(pois.size() - savedDocs.size())
                        .addAllResults(results)
                        .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    /** Normalize the limit value to be within acceptable bounds. */
    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_SEARCH_LIMIT;
        }
        return Math.min(limit, MAX_SEARCH_LIMIT);
    }

    /** Build a search filter from PoiFilter proto. */
    private PoiSearchFilter buildSearchFilter(PoiFilter filter) {
        if (filter == null
                || (filter.getCategoriesList().isEmpty() && filter.getAdcode().isEmpty())) {
            return null;
        }
        return PoiSearchFilter.builder()
                .categories(
                        !filter.getCategoriesList().isEmpty() ? filter.getCategoriesList() : null)
                .adcode(!filter.getAdcode().isEmpty() ? filter.getAdcode() : null)
                .build();
    }
}
