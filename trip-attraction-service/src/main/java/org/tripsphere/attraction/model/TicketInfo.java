package org.tripsphere.attraction.model;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TicketInfo {
    private Money estimatedPrice;
    private Map<String, Object> metadata;
}
