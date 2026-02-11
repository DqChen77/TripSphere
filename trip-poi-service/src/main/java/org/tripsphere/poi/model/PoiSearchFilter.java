package org.tripsphere.poi.model;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PoiSearchFilter {
    private List<String> categories;
    private String adcode;
}
