package org.tripsphere.attraction.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Represents an opening rule for specific days and time ranges. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpenRuleDoc {
    /** Stored as enum name strings, e.g. "DAY_OF_WEEK_MONDAY" */
    private List<String> days;

    private List<TimeRangeDoc> timeRanges;
    private String note;
}
