package org.tripsphere.hotel.repository;

import java.time.Instant;
import java.util.List;
import org.springframework.data.geo.Point;
import org.tripsphere.hotel.model.HotelDoc;

public interface CustomHotelDocRepository {

    /**
     * Find hotels near a given location.
     *
     * @param point the center point in WGS84 coordinate system
     * @param radiusMeters the search radius in meters
     * @param limit the maximum number of results
     * @return list of nearby hotels ordered from near to far
     */
    List<HotelDoc> findAllByLocationNear(Point point, double radiusMeters, int limit);

    /**
     * List hotels with keyset pagination by province and city, ordered by createdAt descending.
     *
     * @param province the province to filter by (can be null or empty)
     * @param city the city to filter by (can be null or empty)
     * @param limit the maximum number of results to return
     * @param cursorCreatedAt the createdAt value from the last item of the previous page (null for
     *     first page)
     * @param cursorId the id from the last item of the previous page (null for first page)
     * @return list of hotels
     */
    List<HotelDoc> findByAddressWithPagination(
            String province, String city, int limit, Instant cursorCreatedAt, String cursorId);
}
