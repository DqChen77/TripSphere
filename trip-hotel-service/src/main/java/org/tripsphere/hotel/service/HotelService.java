package org.tripsphere.hotel.service;

import java.util.List;
import java.util.Optional;
import org.tripsphere.common.v1.GeoPoint;
import org.tripsphere.hotel.v1.Hotel;
import org.tripsphere.hotel.v1.RoomType;

public interface HotelService {

    /**
     * Find a hotel by its ID.
     *
     * @param id the hotel ID
     * @return the hotel if found
     */
    Optional<Hotel> findById(String id);

    /**
     * Find all hotels by their IDs.
     *
     * @param ids the list of hotel IDs
     * @return list of hotels found
     */
    List<Hotel> findAllByIds(List<String> ids);

    /**
     * Search for hotels near a given location.
     *
     * @param location the center point in GCJ-02 coordinate system
     * @param radiusMeters the search radius in meters
     * @return list of nearby hotels ordered from near to far
     */
    List<Hotel> searchNearby(GeoPoint location, double radiusMeters);

    /**
     * List hotels with pagination, optionally filtered by province and city.
     *
     * @param province the province to filter by (can be null or empty)
     * @param city the city to filter by (can be null or empty)
     * @param pageSize the maximum number of results per page
     * @param pageToken the page token from the previous request (cursor-based pagination)
     * @return a page result containing hotels and the next page token
     */
    HotelPage listHotels(String province, String city, int pageSize, String pageToken);

    /**
     * Find all room types for a given hotel.
     *
     * @param hotelId the hotel ID
     * @return list of room types for the hotel
     */
    List<RoomType> findRoomTypesByHotelId(String hotelId);

    /** Represents a paginated result of hotels. */
    record HotelPage(List<Hotel> hotels, String nextPageToken) {}
}
