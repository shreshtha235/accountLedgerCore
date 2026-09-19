package com.ledger;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record LedgerPolicy(long rateNumerator,
                           long rateDenominator,
                           Map<CurrencyCode, Money> overdraftFee,
                           ResidualPolicy instalmentResidual,
                           boolean reopenClosedDaysForFeesCalculation,
                           boolean refundFeeOnReversal) {

    public enum ResidualPolicy {
        DISCARD,
        FIRST,
        LAST
    }

    public LedgerPolicy {
        Objects.requireNonNull(overdraftFee, "overdraftFee");
        Objects.requireNonNull(instalmentResidual, "instalmentResidual");
        if (rateNumerator < 0L) {
            throw new IllegalArgumentException("Rate numerator must not be negative: "
                    + rateNumerator);
        }
        if (rateDenominator <= 0L) {
            throw new IllegalArgumentException("Rate denominator must be positive: "
                    + rateDenominator);
        }
        overdraftFee.forEach((currency, fee) -> {
            if (fee.currency() != currency) {
                throw new IllegalArgumentException("Overdraft fee for " + currency + " is in "
                        + fee.currency());
            }
            if (!fee.isPositive()) {
                throw new IllegalArgumentException("Overdraft fee for " + currency
                        + " must be positive, got " + fee.format());
            }
        });
        overdraftFee = Map.copyOf(overdraftFee);
    }

    public static LedgerPolicy defaults() {
        return new LedgerPolicy(4L, 10_000L,
                Map.of(CurrencyCode.AED, Money.of("25.00", CurrencyCode.AED)),
                ResidualPolicy.DISCARD, false, false);
    }

    public Optional<Money> feeFor(CurrencyCode currency) {
        return Optional.ofNullable(overdraftFee.get(currency));
    }

    public LedgerPolicy withReopenClosedDaysForFeesCalculation(boolean reopen) {
        return new LedgerPolicy(rateNumerator, rateDenominator, overdraftFee, instalmentResidual,
                reopen, refundFeeOnReversal);
    }

    public LedgerPolicy withRefundFeeOnReversal(boolean refund) {
        return new LedgerPolicy(rateNumerator, rateDenominator, overdraftFee, instalmentResidual,
                reopenClosedDaysForFeesCalculation, refund);
    }
}
