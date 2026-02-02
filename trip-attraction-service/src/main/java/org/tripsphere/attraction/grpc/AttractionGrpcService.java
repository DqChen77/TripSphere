package org.tripsphere.attraction.grpc;

import org.tripsphere.attraction.model.AttractionDoc;
import org.tripsphere.attraction.service.AttractionService;
import org.tripsphere.attraction.v1.*;
import org.tripsphere.attraction.v1.AttractionServiceGrpc.AttractionServiceImplBase;
import org.tripsphere.common.v1.Location;

import io.grpc.stub.StreamObserver;

import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class AttractionGrpcService extends AttractionServiceImplBase {

    private final AttractionService attractionService;

    public AttractionGrpcService(AttractionService attractionService) {
        this.attractionService = attractionService;
    }

    @Override
    public void deleteAttraction(
            DeleteAttractionRequest request,
            StreamObserver<DeleteAttractionResponse> responseObserver) {
        String attractionId = request.getId();
        boolean success = attractionService.deleteAttraction(attractionId);
        DeleteAttractionResponse response = DeleteAttractionResponse.newBuilder().build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void findAttractionById(
            FindAttractionByIdRequest request,
            StreamObserver<FindAttractionByIdResponse> responseObserver) {
        AttractionDoc attraction = attractionService.findAttractionById(request.getId());
        if (attraction == null) {
            responseObserver.onError(
                    io.grpc.Status.NOT_FOUND
                            .withDescription("Attraction not found with id: " + request.getId())
                            .asRuntimeException());
            return;
        }
        Attraction.Builder attractionBuilder =
                Attraction.newBuilder()
                        .setId(attraction.getId() == null ? "" : attraction.getId())
                        .setName(attraction.getName() == null ? "" : attraction.getName())
                        .setIntroduction(
                                attraction.getIntroduction() == null
                                        ? ""
                                        : attraction.getIntroduction());

        if (attraction.getTags() != null) attractionBuilder.addAllTags(attraction.getTags());

        if (attraction.getImages() != null) attractionBuilder.addAllImages(attraction.getImages());

        if (attraction.getLocation() != null) {
            Location locationProto =
                    Location.newBuilder()
                            .setLongitude(attraction.getLocation().getX())
                            .setLatitude(attraction.getLocation().getY())
                            .build();
            attractionBuilder.setLocation(locationProto);
        }

        if (attraction.getAddress() != null) {
            org.tripsphere.common.v1.Address.Builder addressBuilder =
                    org.tripsphere.common.v1.Address.newBuilder()
                            .setCountry(
                                    attraction.getAddress().getCountry() == null
                                            ? ""
                                            : attraction.getAddress().getCountry())
                            .setProvince(
                                    attraction.getAddress().getProvince() == null
                                            ? ""
                                            : attraction.getAddress().getProvince())
                            .setCity(
                                    attraction.getAddress().getCity() == null
                                            ? ""
                                            : attraction.getAddress().getCity())
                            .setCounty(
                                    attraction.getAddress().getCounty() == null
                                            ? ""
                                            : attraction.getAddress().getCounty())
                            .setDistrict(
                                    attraction.getAddress().getDistrict() == null
                                            ? ""
                                            : attraction.getAddress().getDistrict())
                            .setStreet(
                                    attraction.getAddress().getStreet() == null
                                            ? ""
                                            : attraction.getAddress().getStreet());
            attractionBuilder.setAddress(addressBuilder.build());
        }

        FindAttractionByIdResponse response =
                FindAttractionByIdResponse.newBuilder()
                        .setAttraction(attractionBuilder.build())
                        .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
