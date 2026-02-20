package org.tripsphere.hotel.repository;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.tripsphere.hotel.model.RoomTypeDoc;

public interface RoomTypeDocRepository extends MongoRepository<RoomTypeDoc, String> {
    List<RoomTypeDoc> findByHotelId(String hotelId);
}
