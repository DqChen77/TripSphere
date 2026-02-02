package org.tripsphere.hotel.grpc;

import org.tripsphere.hotel.service.impl.HotelServiceImpl;
import org.tripsphere.hotel.v1.HotelServiceGrpc.HotelServiceImplBase;

import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class HotelGrpcService extends HotelServiceImplBase {

    private final HotelServiceImpl hotelService;

    public HotelGrpcService(HotelServiceImpl hotelService) {
        this.hotelService = hotelService;
    }
}
