package org.tripsphere.attraction.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;
import org.tripsphere.attraction.model.AttractionDoc;

@Repository
public interface AttractionRepository extends MongoRepository<AttractionDoc, String> {
    public Optional<AttractionDoc> findById(String id);

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
    List<AttractionDoc> findByLocationNear(
            double longitude, double latitude, double maxDistanceMeters);
}
