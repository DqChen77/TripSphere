package org.tripsphere.hotel.api.grpc;

import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import org.tripsphere.hotel.v1.HotelServiceGrpc;

@GrpcService
@RequiredArgsConstructor
public class HotelGrpcService extends HotelServiceGrpc.HotelServiceImplBase {
}
