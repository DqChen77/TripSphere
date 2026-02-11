package org.tripsphere.itinerary.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Embedded document for storing date information. Maps to tripsphere.common.v1.Date proto. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DateDoc {
    private int year;
    private int month;
    private int day;
}
