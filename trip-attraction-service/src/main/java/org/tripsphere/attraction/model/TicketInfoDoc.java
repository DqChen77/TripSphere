package org.tripsphere.attraction.model;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Represents ticket information for an attraction. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TicketInfoDoc {
    private MoneyDoc estimatedPrice;

    /** Flexible metadata stored as a map. */
    private Map<String, Object> metadata;
}
