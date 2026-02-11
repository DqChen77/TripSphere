package org.tripsphere.hotel.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;
import org.tripsphere.hotel.model.HotelDoc;

@Repository
public interface HotelRepository extends MongoRepository<HotelDoc, String> {
    Optional<HotelDoc> findById(String id);

    @Query(
            """
            {
              'location': {
                $nearSphere: {
                  $geometry: { type: 'Point', coordinates: [?0, ?1] },
                  $maxDistance: ?2
                }
              }
            }
            """)
    List<HotelDoc> findByLocationNearWithFilters(
            double longitude, double latitude, double maxDistanceMeters);
}
