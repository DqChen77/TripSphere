package org.tripsphere.attraction.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Represents an amount of money with currency. */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MoneyDoc {
    /** The three-letter currency code defined in ISO 4217. */
    private String currency;

    /** The whole units of the amount. */
    private long units;

    /** Number of nano (10^-9) units of the amount. */
    private int nanos;
}
