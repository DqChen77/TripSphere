package org.tripsphere.itinerary.repository;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.tripsphere.itinerary.model.ItineraryDoc;

/** MongoDB repository for itinerary documents. */
public interface ItineraryRepository extends MongoRepository<ItineraryDoc, String> {

    /**
     * Find itineraries for a user with cursor-based pagination. Uses createdAt and id as cursor
     * keys for stable, efficient pagination.
     *
     * <p>The query finds records where: (createdAt < cursorCreatedAt) OR (createdAt ==
     * cursorCreatedAt AND _id < cursorId)
     *
     * <p>This ensures efficient index usage with a compound index on {userId, archived, createdAt,
     * _id}.
     *
     * @param userId the user ID
     * @param archived the archived flag
     * @param cursorCreatedAt the createdAt value from the last item of previous page
     * @param cursorId the id value from the last item of previous page
     * @param pageable pagination information (only limit is used)
     * @return list of itinerary documents
     */
    @Query(
            value =
                    """
                    {
                      'userId': ?0,
                      'archived': ?1,
                      '$or': [
                        { 'createdAt': { '$lt': ?2 } },
                        { 'createdAt': ?2, '_id': { '$lt': ?3 } }
                      ]
                    }
                    """,
            sort = "{ 'createdAt': -1, '_id': -1 }")
    List<ItineraryDoc> findByUserIdWithCursor(
            String userId,
            boolean archived,
            Instant cursorCreatedAt,
            String cursorId,
            Pageable pageable);

    /**
     * Find the first page of itineraries for a user (no cursor). Ordered by createdAt descending,
     * then by id descending for stable ordering.
     *
     * @param userId the user ID
     * @param archived the archived flag
     * @param pageable pagination information (only limit is used)
     * @return list of itinerary documents
     */
    List<ItineraryDoc> findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
            String userId, boolean archived, Pageable pageable);
}
