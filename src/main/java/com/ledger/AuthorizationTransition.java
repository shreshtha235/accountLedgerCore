package com.ledger;

import java.util.Objects;

public record AuthorizationTransition(String authRef, String accountId, Money amount, int day,
                                      State toState, String reason) {

    public enum State {
        APPROVED,
        DECLINED,
        SETTLED,
        RELEASED
    }

    public AuthorizationTransition {
        Objects.requireNonNull(authRef, "authRef");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(toState, "toState");
        Objects.requireNonNull(reason, "reason");
        if (authRef.isBlank()) {
            throw new IllegalArgumentException("Authorization reference must not be blank");
        }
        if (day < 1) {
            throw new IllegalArgumentException(
                    "Authorization " + authRef + " has day " + day);
        }
        if (amount.isNegative()) {
            throw new IllegalArgumentException("Authorization " + authRef
                    + " must carry a positive magnitude, got " + amount.format());
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Authorization " + authRef + " transition must state a reason");
        }
    }
}
