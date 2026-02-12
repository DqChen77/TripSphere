package org.tripsphere.attraction.model;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpenRule {
    private List<DayOfWeek> days;
    private List<TimeRange> timeRanges;
    private String note;

    public record TimeRange(LocalTime openTime, LocalTime closeTime, LocalTime lastEntryTime) {}
}
