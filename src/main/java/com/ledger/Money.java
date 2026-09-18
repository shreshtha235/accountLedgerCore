package com.ledger;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record Money(long minor, CurrencyCode currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money ofMinor(long minor, CurrencyCode currency) {
        return new Money(minor, currency);
    }

    public static Money zero(CurrencyCode currency) {
        return new Money(0L, currency);
    }

    public static Money of(String decimal, CurrencyCode currency) {
        Objects.requireNonNull(decimal, "decimal");
        Objects.requireNonNull(currency, "currency");

        String text = decimal.strip().replace(",", "");
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Blank amount: '" + decimal + "'");
        }

        boolean negative = text.charAt(0) == '-';
        if (negative || text.charAt(0) == '+') {
            text = text.substring(1);
        }

        int dot = text.indexOf('.');
        String whole = dot < 0 ? text : text.substring(0, dot);
        String fraction = dot < 0 ? "" : text.substring(dot + 1);

        if (whole.isEmpty()) {
            whole = "0";
        }
        requireDigits(whole, decimal);
        requireDigits(fraction, decimal);

        if (fraction.length() > currency.scale()) {
            throw new IllegalArgumentException("Amount '" + decimal + "' has more than "
                    + currency.scale() + " decimal places for " + currency);
        }

        String padded = fraction + "0".repeat(currency.scale() - fraction.length());
        long units = Math.multiplyExact(Long.parseLong(whole), currency.minorUnitsPerUnit());
        long minorUnits = padded.isEmpty() ? units : Math.addExact(units, Long.parseLong(padded));
        return new Money(negative ? Math.negateExact(minorUnits) : minorUnits, currency);
    }

    private static void requireDigits(String text, String original) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("Not a valid amount: '" + original + "'");
            }
        }
    }

    public static long roundHalfUp(long numerator, long denominator) {
        if (denominator == 0L) {
            throw new ArithmeticException("Division by zero");
        }
        long num = numerator;
        long den = denominator;
        if (den < 0L) {
            num = Math.negateExact(num);
            den = Math.negateExact(den);
        }
        long quotient = num / den;
        long remainder = num % den;
        if (remainder == 0L) {
            return quotient;
        }
        long magnitude = Math.abs(remainder);
        if (magnitude < den - magnitude) {
            return quotient;
        }
        return num > 0L ? Math.incrementExact(quotient) : Math.decrementExact(quotient);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    public Money negated() {
        return new Money(Math.negateExact(minor), currency);
    }

    public Money applyRate(long rateNumerator, long rateDenominator) {
        long scaled = Math.multiplyExact(minor, rateNumerator);
        return new Money(roundHalfUp(scaled, rateDenominator), currency);
    }

    public List<Money> divideEqually(int parts) {
        if (parts < 1) {
            throw new IllegalArgumentException("parts must be at least 1, got " + parts);
        }
        return Collections.nCopies(parts, new Money(minor / parts, currency));
    }

    public boolean isNegative() {
        return minor < 0L;
    }

    public boolean isPositive() {
        return minor > 0L;
    }

    public boolean isZero() {
        return minor == 0L;
    }

    public String format() {
        long magnitude = Math.absExact(minor);
        String sign = minor < 0L ? "-" : "";
        long units = magnitude / currency.minorUnitsPerUnit();
        if (currency.scale() == 0) {
            return "%s %s%d".formatted(currency, sign, units);
        }
        long fraction = magnitude % currency.minorUnitsPerUnit();
        String pattern = "%s %s%d.%0" + currency.scale() + "d";
        return String.format(pattern, currency, sign, units, fraction);
    }

    @Override
    public String toString() {
        return format();
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (currency != other.currency) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currency + " and " + other.currency);
        }
    }
}
