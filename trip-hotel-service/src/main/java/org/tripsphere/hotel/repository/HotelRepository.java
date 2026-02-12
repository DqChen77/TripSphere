package org.tripsphere.hotel.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.tripsphere.hotel.model.HotelDoc;

@Repository
public interface HotelRepository extends MongoRepository<HotelDoc, String> {}
