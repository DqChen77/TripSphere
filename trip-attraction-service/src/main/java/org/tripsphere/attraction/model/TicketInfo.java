package org.tripsphere.attraction.model;

import java.math.BigDecimal;
import java.util.Currency;
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

    public record Money(Currency currency, BigDecimal amount) {}
}
