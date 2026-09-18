package com.ledger;

import java.util.Objects;

public record Account(String id, CurrencyCode currency, Money openingBalance) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(openingBalance, "openingBalance");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Account id must not be blank");
        }
        if (openingBalance.currency() != currency) {
            throw new IllegalArgumentException("Account " + id + " is " + currency
                    + " but its opening balance is " + openingBalance.currency());
        }
    }
}
