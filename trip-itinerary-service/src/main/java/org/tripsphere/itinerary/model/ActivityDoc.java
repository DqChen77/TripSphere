package org.tripsphere.itinerary.model;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Embedded document for storing activity information within a day plan. Maps to
 * tripsphere.itinerary.v1.Activity proto.
 *
 * <p>Note: Only stores reference IDs for external resources (attraction, hotel). The full resource
 * data is fetched from respective services when needed.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ActivityDoc {
    private String id;
    private ActivityKind kind;
    private String title;
    private String description;
    private TimeOfDayDoc startTime;
    private TimeOfDayDoc endTime;
    private MoneyDoc estimatedCost;

    // Resource references - only store IDs, mutually exclusive based on kind
    private String attractionId;
    private String hotelId;

    private Map<String, Object> metadata;
}
