package org.tripsphere.poi.repository;

import java.util.List;
import org.springframework.data.geo.Point;
import org.tripsphere.poi.model.PoiDoc;
import org.tripsphere.poi.model.PoiSearchFilter;

public interface CustomPoiRepository {
    public List<PoiDoc> findAllByLocationNear(
            Point point, double radiusMeters, int limit, PoiSearchFilter searchFilter);

    public List<PoiDoc> findAllByLocationInBox(
            Point southWest, Point northEast, int limit, PoiSearchFilter searchFilter);
}
