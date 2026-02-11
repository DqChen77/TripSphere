package org.tripsphere.attraction.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Represents the opening hours of an attraction. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class OpeningHoursDoc {
    private List<OpenRuleDoc> rules;
    private String specialTips;
}
