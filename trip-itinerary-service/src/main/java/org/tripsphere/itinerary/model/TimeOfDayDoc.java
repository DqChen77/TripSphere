package org.tripsphere.itinerary.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Embedded document for storing time of day. Maps to tripsphere.common.v1.TimeOfDay proto. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TimeOfDayDoc {
    private int hours;
    private int minutes;
    private int seconds;
    private int nanos;
}
