package com.ledger;

import java.util.Objects;

public sealed interface LedgerEvent {

    String id();

    int bookingDay();

    String accountId();

    int valueDay();

    record Credit(String id, int bookingDay, String accountId, Money amount, int valueDay,
                  int instalments) implements LedgerEvent {

        public Credit {
            validateCommon(id, bookingDay, accountId, valueDay);
            requirePositiveAmount(amount, id);
            if (instalments < 1) {
                throw new IllegalArgumentException(
                        "Event " + id + " must have at least 1 instalment, got " + instalments);
            }
        }
    }

    record Debit(String id, int bookingDay, String accountId, Money amount, int valueDay)
            implements LedgerEvent {

        public Debit {
            validateCommon(id, bookingDay, accountId, valueDay);
            requirePositiveAmount(amount, id);
        }
    }

    record Authorize(String id, int bookingDay, String accountId, String authRef, Money amount,
                     int valueDay) implements LedgerEvent {

        public Authorize {
            validateCommon(id, bookingDay, accountId, valueDay);
            requireRef(authRef, id);
            requirePositiveAmount(amount, id);
        }
    }

    record Settle(String id, int bookingDay, String accountId, String authRef, Money amount,
                  int valueDay) implements LedgerEvent {

        public Settle {
            validateCommon(id, bookingDay, accountId, valueDay);
            requireRef(authRef, id);
            requirePositiveAmount(amount, id);
        }
    }

    record Reversal(String id, int bookingDay, String accountId, String reversesEventId,
                    int valueDay) implements LedgerEvent {

        public Reversal {
            validateCommon(id, bookingDay, accountId, valueDay);
            requireRef(reversesEventId, id);
        }
    }

    private static void validateCommon(String id, int bookingDay, String accountId, int valueDay) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Event id must not be blank");
        }
        if (accountId.isBlank()) {
            throw new IllegalArgumentException("Event " + id + " has a blank account id");
        }
        if (bookingDay < 1) {
            throw new IllegalArgumentException("Event " + id + " has booking day " + bookingDay);
        }
        if (valueDay < 1) {
            throw new IllegalArgumentException("Event " + id + " has value day " + valueDay);
        }
    }

    private static void requirePositiveAmount(Money amount, String id) {
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "Event " + id + " must carry a positive amount, got " + amount.format());
        }
    }

    private static void requireRef(String ref, String id) {
        Objects.requireNonNull(ref, "ref");
        if (ref.isBlank()) {
            throw new IllegalArgumentException("Event " + id + " has a blank reference");
        }
    }
}
