package org.tripsphere.hotel.grpc;

import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import org.tripsphere.hotel.v1.HotelServiceGrpc.HotelServiceImplBase;

@GrpcService
@RequiredArgsConstructor
public class HotelGrpcService extends HotelServiceImplBase {
    // private final HotelServiceImpl hotelService;
}
