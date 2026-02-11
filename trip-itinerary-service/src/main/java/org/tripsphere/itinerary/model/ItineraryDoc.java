package org.tripsphere.itinerary.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * MongoDB document for storing itinerary information. Maps to tripsphere.itinerary.v1.Itinerary
 * proto.
 *
 * <p>Note: Only stores destinationPoiId reference. The full POI data is fetched from PoiService
 * when needed.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "itineraries")
@CompoundIndex(
        name = "user_archived_date_idx",
        def = "{'userId': 1, 'archived': 1, 'startDate': -1}")
public class ItineraryDoc {
    @Id private String id;

    private String title;

    @Indexed private String userId;

    /** Reference to the destination POI. Only stores the POI ID. */
    private String destinationPoiId;

    private DateDoc startDate;
    private DateDoc endDate;

    @Builder.Default private List<DayPlanDoc> dayPlans = new ArrayList<>();

    private Map<String, Object> metadata;

    /** Soft delete flag for ArchiveItinerary operation. */
    @Builder.Default private boolean archived = false;

    @CreatedDate private Instant createdAt;

    @LastModifiedDate private Instant updatedAt;
}
