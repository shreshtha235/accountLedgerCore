package com.ledger;

import java.util.Objects;

public record Accrual(String accountId, int day, Money amount) {

    public Accrual {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        if (accountId.isBlank()) {
            throw new IllegalArgumentException("Accrual account id must not be blank");
        }
        if (day < 1) {
            throw new IllegalArgumentException("Accrual day must be at least 1: " + day);
        }
        if (amount.isNegative()) {
            throw new IllegalArgumentException("Accrual for " + accountId + " on day " + day
                    + " must not be negative, got " + amount.format());
        }
    }
}
