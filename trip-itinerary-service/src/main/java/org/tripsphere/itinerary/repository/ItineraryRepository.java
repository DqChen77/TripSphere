package org.tripsphere.itinerary.repository;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.tripsphere.itinerary.model.ItineraryDoc;

/** MongoDB repository for itinerary documents. */
public interface ItineraryRepository extends MongoRepository<ItineraryDoc, String> {

    /**
     * Find all non-archived itineraries for a user, ordered by start date descending.
     *
     * @param userId the user ID
     * @param archived the archived flag
     * @param pageable pagination information
     * @return list of itinerary documents
     */
    List<ItineraryDoc> findByUserIdAndArchivedOrderByStartDateDesc(
            String userId, boolean archived, Pageable pageable);

    /**
     * Count non-archived itineraries for a user.
     *
     * @param userId the user ID
     * @param archived the archived flag
     * @return count of itineraries
     */
    long countByUserIdAndArchived(String userId, boolean archived);
}
