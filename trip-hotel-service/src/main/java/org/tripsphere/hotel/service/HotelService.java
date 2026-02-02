package org.tripsphere.hotel.service;

import org.tripsphere.hotel.model.HotelDoc;

public interface HotelService {
    boolean deleteHotel(String id);

    HotelDoc findHotelById(String id);
}
