package com.ledger;

import java.util.Objects;
import java.util.Optional;

public record LedgerError(int day, String eventId, Code code, String detail) {

    public enum Code {
        ORPHAN_SETTLEMENT,
        AUTHORIZATION_NOT_OPEN,
        AUTHORIZATION_DECLINED,
        OVER_SETTLEMENT,
        INSTALMENT_RESIDUAL_DISCARDED,
        FEE_CURRENCY_UNDEFINED,
        REVERSAL_TARGET_NOT_FOUND,
        DUPLICATE_REVERSAL,
        REVERSAL_OF_REVERSAL,
        REVERSAL_MISMATCH
    }

    public LedgerError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
        if (day < 1) {
            throw new IllegalArgumentException("Error day must be at least 1: " + day);
        }
        if (eventId != null && eventId.isBlank()) {
            throw new IllegalArgumentException("Error event id must not be blank when present");
        }
        if (detail.isBlank()) {
            throw new IllegalArgumentException("Error " + code + " must state a detail");
        }
    }

    public static LedgerError forEvent(int day, String eventId, Code code, String detail) {
        Objects.requireNonNull(eventId, "eventId");
        return new LedgerError(day, eventId, code, detail);
    }

    public static LedgerError system(int day, Code code, String detail) {
        return new LedgerError(day, null, code, detail);
    }

    public Optional<String> sourceEvent() {
        return Optional.ofNullable(eventId);
    }
}
