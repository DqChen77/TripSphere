package org.tripsphere.itinerary.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Embedded document for storing day plan information within an itinerary. Maps to
 * tripsphere.itinerary.v1.DayPlan proto.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DayPlanDoc {
    private String id;
    private DateDoc date;
    private String title;

    @Builder.Default private List<ActivityDoc> activities = new ArrayList<>();

    private String notes;
    private Map<String, Object> metadata;
}
