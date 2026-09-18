package com.ledger;

import static com.ledger.CurrencyCode.AED;
import static com.ledger.CurrencyCode.BHD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void parsesToTheCurrencyOwnScale() {
        assertEquals(120_000L, Money.of("1200.00", AED).minor());
        assertEquals(10_000L, Money.of("10.000", BHD).minor());
        assertEquals(2_500L, Money.of("25.00", AED).minor());
    }

    @Test
    void stripsThousandsSeparators() {
        assertEquals(120_000L, Money.of("1,200.00", AED).minor());
    }

    @Test
    void padsMissingDecimalPlaces() {
        assertEquals(120_000L, Money.of("1200", AED).minor());
        assertEquals(120_050L, Money.of("1200.5", AED).minor());
    }

    @Test
    void parsesNegativeAmounts() {
        assertEquals(-37_000L, Money.of("-370.00", AED).minor());
    }

    @Test
    @DisplayName("more decimals than the currency allows is a wrong number, not a formatting issue")
    void rejectsExcessPrecision() {
        assertThrows(IllegalArgumentException.class, () -> Money.of("1.234", AED));
        assertThrows(IllegalArgumentException.class, () -> Money.of("1.2345", BHD));
    }

    @Test
    void rejectsMalformedAmounts() {
        assertThrows(IllegalArgumentException.class, () -> Money.of("abc", AED));
        assertThrows(IllegalArgumentException.class, () -> Money.of("", AED));
        assertThrows(IllegalArgumentException.class, () -> Money.of("   ", AED));
        assertThrows(IllegalArgumentException.class, () -> Money.of("1.2.3", AED));
    }

    @Test
    void addsAndSubtractsExactly() {
        Money credit = Money.of("1200.00", AED);
        Money debit = Money.of("950.00", AED);
        assertEquals(Money.of("250.00", AED), credit.minus(debit));
        assertEquals(Money.of("2150.00", AED), credit.plus(debit));
        assertEquals(Money.of("-950.00", AED), debit.negated());
    }

    @Test
    void refusesToMixCurrencies() {
        Money dirhams = Money.of("1.00", AED);
        Money dinars = Money.of("1.000", BHD);
        assertThrows(IllegalArgumentException.class, () -> dirhams.plus(dinars));
        assertThrows(IllegalArgumentException.class, () -> dirhams.minus(dinars));
    }

    @Test
    @DisplayName("zero is neither positive nor negative, so it attracts no fee and no interest")
    void zeroIsStrictlyNeither() {
        Money zero = Money.zero(AED);
        assertFalse(zero.isPositive());
        assertFalse(zero.isNegative());
        assertTrue(zero.isZero());
    }

    @Test
    void formatsToItsOwnScale() {
        assertEquals("AED 250.00", Money.of("250.00", AED).format());
        assertEquals("AED -370.00", Money.of("-370.00", AED).format());
        assertEquals("BHD 9.999", Money.of("9.999", BHD).format());
        assertEquals("BHD 0.004", Money.ofMinor(4L, BHD).format());
    }

    @Test
    void roundsHalfAwayFromZero() {
        assertEquals(1L, Money.roundHalfUp(1L, 2L));
        assertEquals(-1L, Money.roundHalfUp(-1L, 2L));
        assertEquals(2L, Money.roundHalfUp(3L, 2L));
        assertEquals(-2L, Money.roundHalfUp(-3L, 2L));
    }

    @Test
    void roundsEitherSideOfHalf() {
        assertEquals(0L, Money.roundHalfUp(1L, 3L));
        assertEquals(1L, Money.roundHalfUp(2L, 3L));
        assertEquals(5L, Money.roundHalfUp(10L, 2L));
    }

    @Test
    void normalisesANegativeDenominator() {
        assertEquals(-1L, Money.roundHalfUp(1L, -2L));
    }

    @Test
    void rejectsAZeroDenominator() {
        assertThrows(ArithmeticException.class, () -> Money.roundHalfUp(1L, 0L));
    }

    @Test
    @DisplayName("the five daily accruals this ledger actually produces")
    void appliesTheDailyRate() {
        assertEquals(Money.of("0.10", AED), Money.of("250.00", AED).applyRate(4L, 10_000L));
        assertEquals(Money.of("0.26", AED), Money.of("650.00", AED).applyRate(4L, 10_000L));
        assertEquals(Money.of("0.19", AED), Money.of("465.00", AED).applyRate(4L, 10_000L));
        assertEquals(Money.of("0.18", AED), Money.of("440.00", AED).applyRate(4L, 10_000L));
        assertEquals(Money.of("0.004", BHD), Money.of("9.999", BHD).applyRate(4L, 10_000L));
    }

    @Test
    @DisplayName("a balance of 12.50 accrues exactly half a fil, which rounds up to one")
    void appliesTheRateAtAnExactHalf() {
        assertEquals(Money.of("0.01", AED), Money.of("12.50", AED).applyRate(4L, 10_000L));
    }

    @Test
    void dividesEquallyByFlooringEveryPart() {
        Money total = Money.of("10.000", BHD);
        List<Money> parts = total.divideEqually(3);

        assertEquals(3, parts.size());
        parts.forEach(part -> assertEquals(Money.of("3.333", BHD), part));

        Money sum = Money.zero(BHD);
        for (Money part : parts) {
            sum = sum.plus(part);
        }
        assertEquals(Money.of("9.999", BHD), sum);
        assertEquals(Money.ofMinor(1L, BHD), total.minus(sum));
    }

    @Test
    void rejectsAnImpossibleNumberOfParts() {
        assertThrows(IllegalArgumentException.class, () -> Money.of("1.00", AED).divideEqually(0));
    }
}
