package org.tripsphere.attraction.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Represents a time of day without date or timezone. */
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
