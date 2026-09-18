package com.ledger;

import java.util.Objects;
import java.util.Optional;

public record Posting(long seq, String eventId, String accountId, Money amount, int valueDay,
                      int bookingDay, Type type, String reversesEventId) {

    public enum Type {
        OPENING_BALANCE,
        CREDIT,
        DEBIT,
        SETTLEMENT,
        REVERSAL,
        OVERDRAFT_FEE,
        INTEREST_CAPITALISATION,
        FEE_REFUND
    }

    public Posting {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(type, "type");
        if (seq < 0L) {
            throw new IllegalArgumentException("Posting sequence must not be negative: " + seq);
        }
        if (valueDay < 1) {
            throw new IllegalArgumentException("Posting value day must be at least 1: " + valueDay);
        }
        if (bookingDay < 1) {
            throw new IllegalArgumentException(
                    "Posting booking day must be at least 1: " + bookingDay);
        }
        if (type == Type.REVERSAL) {
            if (reversesEventId == null || reversesEventId.isBlank()) {
                throw new IllegalArgumentException(
                        "Reversal posting " + seq + " must name the event it reverses");
            }
        } else if (reversesEventId != null) {
            throw new IllegalArgumentException(
                    type + " posting " + seq + " must not name a reversed event");
        }
        switch (type) {
            case DEBIT, SETTLEMENT, OVERDRAFT_FEE -> {
                if (amount.isPositive()) {
                    throw new IllegalArgumentException(
                            type + " must not be positive, got " + amount.format());
                }
            }
            case CREDIT, INTEREST_CAPITALISATION, FEE_REFUND -> {
                if (amount.isNegative()) {
                    throw new IllegalArgumentException(
                            type + " must not be negative, got " + amount.format());
                }
            }
            case OPENING_BALANCE, REVERSAL -> {
            }
        }
    }

    public Optional<String> reversalTarget() {
        return Optional.ofNullable(reversesEventId);
    }
}
