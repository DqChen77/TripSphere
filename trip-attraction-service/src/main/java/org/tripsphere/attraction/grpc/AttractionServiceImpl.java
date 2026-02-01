package org.tripsphere.attraction.grpc;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.tripsphere.attraction.model.Address;
import org.tripsphere.attraction.model.AttractionEntity;
import org.tripsphere.attraction.service.AttractionService;
import org.tripsphere.attraction.v1.*;
import org.tripsphere.attraction.v1.AttractionServiceGrpc.AttractionServiceImplBase;
import org.tripsphere.common.v1.Location;

import io.grpc.stub.StreamObserver;

import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class AttractionServiceImpl extends AttractionServiceImplBase {

    private final AttractionService attractionService;

    public AttractionServiceImpl(AttractionService attractionService) {
        this.attractionService = attractionService;
    }

    @Override
    public void addAttraction(
            AddAttractionRequest request, StreamObserver<AddAttractionResponse> responseObserver) {
        AttractionEntity attraction = new AttractionEntity();

        Address address = new Address();
        address.setCountry(request.getAttraction().getAddress().getCountry());
        address.setProvince(request.getAttraction().getAddress().getProvince());
        address.setCity(request.getAttraction().getAddress().getCity());
        address.setCounty(request.getAttraction().getAddress().getCounty());
        address.setDistrict(request.getAttraction().getAddress().getDistrict());
        address.setStreet(request.getAttraction().getAddress().getStreet());
        attraction.setAddress(address);

        attraction.setIntroduction(request.getAttraction().getIntroduction());
        attraction.setTags(request.getAttraction().getTagsList());
        attraction.setName(request.getAttraction().getName());

        GeoJsonPoint location =
                new GeoJsonPoint(
                        request.getAttraction().getLocation().getLongitude(),
                        request.getAttraction().getLocation().getLatitude());
        attraction.setLocation(location);

        attraction.setImages(request.getAttraction().getImagesList());

        String id = attractionService.addAttraction(attraction);

        AddAttractionResponse response = AddAttractionResponse.newBuilder().setId(id).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void deleteAttraction(
            DeleteAttractionRequest request,
            StreamObserver<DeleteAttractionResponse> responseObserver) {
        String attractionId = request.getId();
        boolean success = attractionService.deleteAttraction(attractionId);
        DeleteAttractionResponse response =
                DeleteAttractionResponse.newBuilder().setSuccess(success).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void changeAttraction(
            ChangeAttractionRequest request,
            StreamObserver<ChangeAttractionResponse> responseObserver) {
        String name = request.getAttraction().getName();

        String country = request.getAttraction().getAddress().getCountry();
        String province = request.getAttraction().getAddress().getProvince();
        String city = request.getAttraction().getAddress().getCity();
        String county = request.getAttraction().getAddress().getCounty();
        String district = request.getAttraction().getAddress().getDistrict();
        String street = request.getAttraction().getAddress().getStreet();
        Address address = new Address();
        address.setCountry(country);
        address.setProvince(province);
        address.setCity(city);
        address.setCounty(county);
        address.setDistrict(district);
        address.setStreet(street);

        String introduction = request.getAttraction().getIntroduction();
        List<String> tags = request.getAttraction().getTagsList();

        List<String> images = request.getAttraction().getImagesList();

        GeoJsonPoint location =
                new GeoJsonPoint(
                        request.getAttraction().getLocation().getLongitude(),
                        request.getAttraction().getLocation().getLatitude());

        AttractionEntity attraction = new AttractionEntity();
        attraction.setId(request.getAttraction().getId());
        attraction.setName(name);
        attraction.setAddress(address);
        attraction.setIntroduction(introduction);
        attraction.setTags(tags);
        attraction.setImages(images);
        attraction.setLocation(location);

        boolean success = attractionService.changAttraction(attraction);
        ChangeAttractionResponse response =
                ChangeAttractionResponse.newBuilder().setSuccess(success).build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void findAttractionById(
            FindAttractionByIdRequest request,
            StreamObserver<FindAttractionByIdResponse> responseObserver) {
        AttractionEntity attraction = attractionService.findAttractionById(request.getId());
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

    @Override
    public void findAttractionsWithinRadiusPage(
            FindAttractionsWithinRadiusPageRequest request,
            StreamObserver<FindAttractionsWithinRadiusPageResponse> responseObserver) {
        double lng = request.getLocation().getLongitude();
        double lat = request.getLocation().getLatitude();
        double radiusKm = request.getRadiusKm();
        int number = request.getNumber();
        int size = request.getSize();
        String name = request.getName();
        List<String> tags = request.getTagsList();

        Page<AttractionEntity> result =
                attractionService.findAttractionsWithinRadius(
                        lng, lat, radiusKm, name, tags, number, size);

        AttractionPage attractionPage = buildAttractionPage(result);
        FindAttractionsWithinRadiusPageResponse response =
                FindAttractionsWithinRadiusPageResponse.newBuilder()
                        .setAttractionPage(attractionPage)
                        .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void findAttractionsWithinCirclePage(
            FindAttractionsWithinCirclePageRequest request,
            StreamObserver<FindAttractionsWithinCirclePageResponse> responseObserver) {
        double lng = request.getLocation().getLongitude();
        double lat = request.getLocation().getLatitude();
        double radiusKm = request.getRadiusKm();
        int number = request.getNumber();
        int size = request.getSize();
        String name = request.getName();
        List<String> tags = request.getTagsList();

        Page<AttractionEntity> result =
                attractionService.findAttractionsWithinCircle(
                        lng, lat, radiusKm, name, tags, number, size);

        AttractionPage attractionPage = buildAttractionPage(result);
        FindAttractionsWithinCirclePageResponse response =
                FindAttractionsWithinCirclePageResponse.newBuilder()
                        .setAttractionPage(attractionPage)
                        .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void findAttractionsWithinRadius(
            FindAttractionsWithinRadiusRequest request,
            StreamObserver<FindAttractionsWithinRadiusResponse> responseObserver) {
        double lng = request.getLocation().getLongitude();
        double lat = request.getLocation().getLatitude();
        double radiusKm = request.getRadiusKm();
        String name = request.getName();
        List<String> tags = request.getTagsList();

        List<AttractionEntity> attractions =
                attractionService.findAttractionsWithinRadius(lng, lat, radiusKm, name, tags);

        List<Attraction> attractionProtos = buildAttractionList(attractions);
        FindAttractionsWithinRadiusResponse response =
                FindAttractionsWithinRadiusResponse.newBuilder()
                        .addAllContent(attractionProtos)
                        .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void findAttractionsWithinCircle(
            FindAttractionsWithinCircleRequest request,
            StreamObserver<FindAttractionsWithinCircleResponse> responseObserver) {
        double lng = request.getLocation().getLongitude();
        double lat = request.getLocation().getLatitude();
        double radiusKm = request.getRadiusKm();
        String name = request.getName();
        List<String> tags = request.getTagsList();

        List<AttractionEntity> attractions =
                attractionService.findAttractionsWithinCircle(lng, lat, radiusKm, name, tags);

        List<Attraction> attractionProtos = buildAttractionList(attractions);
        FindAttractionsWithinCircleResponse response =
                FindAttractionsWithinCircleResponse.newBuilder()
                        .addAllContent(attractionProtos)
                        .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    /***********************************************************************************************/
    /** Page<Attraction> ->proto AttractionPage */
    private AttractionPage buildAttractionPage(Page<AttractionEntity> result) {
        List<Attraction> attractionProtos = buildAttractionList(result.getContent());

        return AttractionPage.newBuilder()
                .addAllContent(attractionProtos)
                .setTotalPages(result.getTotalPages())
                .setTotalElements(result.getTotalElements())
                .setSize(result.getSize())
                .setNumber(result.getNumber())
                .setFirst(result.isFirst())
                .setLast(result.isLast())
                .setNumberOfElements(result.getNumberOfElements())
                .build();
    }

    /** List<Hotel> -> List<proto Hotel> */
    private List<Attraction> buildAttractionList(List<AttractionEntity> attractions) {
        List<Attraction> attractionProtos = new ArrayList<>();

        for (AttractionEntity attraction : attractions) {
            Attraction.Builder attractionBuilder =
                    Attraction.newBuilder()
                            .setId(attraction.getId() == null ? "" : attraction.getId())
                            .setName(attraction.getName() == null ? "" : attraction.getName())
                            .setIntroduction(
                                    attraction.getIntroduction() == null
                                            ? ""
                                            : attraction.getIntroduction());

            if (attraction.getTags() != null) attractionBuilder.addAllTags(attraction.getTags());

            if (attraction.getImages() != null)
                attractionBuilder.addAllImages(attraction.getImages());

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
            attractionProtos.add(attractionBuilder.build());
        }
        return attractionProtos;
    }
}
