package org.tripsphere.attraction.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Represents a time range with open, close, and optional last entry times. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TimeRangeDoc {
    private TimeOfDayDoc openTime;
    private TimeOfDayDoc closeTime;
    private TimeOfDayDoc lastEntryTime;
}
