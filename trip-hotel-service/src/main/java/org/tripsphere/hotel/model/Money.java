package org.tripsphere.hotel.model;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Represents an amount of money with its currency type.
 *
 * @param currency the three-letter currency code defined in ISO 4217
 * @param amount the monetary amount
 */
public record Money(Currency currency, BigDecimal amount) {
    public Money {
        Objects.requireNonNull(currency, "currency cannot be null");
        Objects.requireNonNull(amount, "amount cannot be null");
    }
}
