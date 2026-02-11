package org.tripsphere.hotel.service.impl;

// import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.tripsphere.hotel.model.HotelDoc;
import org.tripsphere.hotel.repository.HotelRepository;
import org.tripsphere.hotel.service.HotelService;

@Service
@Slf4j
public class HotelServiceImpl implements HotelService {
    // private static final DateTimeFormatter DATE_TIME_FORMATTER =
    //         DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // private static final DateTimeFormatter DATE_FORMATTER =
    //         DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final HotelRepository hotelRepository;

    public HotelServiceImpl(HotelRepository hotelRepository) {
        this.hotelRepository = hotelRepository;
    }

    public boolean deleteHotel(String id) {
        Optional<HotelDoc> hotelOptional = hotelRepository.findById(id);
        if (hotelOptional.isPresent()) {
            HotelDoc hotel = hotelOptional.get();
            hotelRepository.delete(hotel);
        } else return false;
        return true;
    }

    public HotelDoc findHotelById(String id) {
        Optional<HotelDoc> hotelOptional = hotelRepository.findById(id);
        if (hotelOptional.isPresent()) {
            HotelDoc hotel = hotelOptional.get();
            return hotel;
        } else return null;
    }

    /**
     * Find hotels located at the specified location and within the specified distance range, and
     * list them in order from near to far.
     *
     * @param longitude Longitude
     * @param latitude Latitude
     * @param radiusKm distance to center(km)
     * @return The list of hotels
     */
    public List<HotelDoc> findHotelsWithinRadius(
            double longitude, double latitude, double radiusKm, String name, List<String> tags) {
        double maxDistanceMeters = radiusKm * 1000; // Mongo expects meters for $nearSphere
        // String nameRegex = (name == null || name.isBlank()) ? ".*" : ".*" + name + ".*";
        return hotelRepository.findByLocationNearWithFilters(
                longitude, latitude, maxDistanceMeters);
    }
}
