package org.tripsphere.itinerary.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Embedded document for storing monetary values. Maps to tripsphere.common.v1.Money proto. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MoneyDoc {
    /** The three-letter currency code defined in ISO 4217. */
    private String currency;

    private long units;
    private int nanos;
}
