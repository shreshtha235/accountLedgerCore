package com.ledger;

import static com.ledger.CurrencyCode.AED;
import static com.ledger.CurrencyCode.BHD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LedgerEngineTest {

    private static final String ACC = "ACC-001";
    private static final String BHD_ACC = "ACC-002";

    private static Money aed(String amount) {
        return Money.of(amount, AED);
    }

    private static Money bhd(String amount) {
        return Money.of(amount, BHD);
    }

    private static Ledger replay(List<LedgerEvent> events, int lastDay) {
        return replay(events, lastDay, LedgerPolicy.defaults());
    }

    private static Ledger replay(List<LedgerEvent> events, int lastDay, LedgerPolicy policy) {
        return new LedgerEngine(List.of(new Account(ACC, AED, Money.zero(AED))), policy)
                .replay(events, 1, lastDay);
    }

    private static Ledger replayBhd(List<LedgerEvent> events, int lastDay, LedgerPolicy policy) {
        return new LedgerEngine(List.of(new Account(BHD_ACC, BHD, Money.zero(BHD))), policy)
                .replay(events, 1, lastDay);
    }

    private static boolean hasError(Ledger ledger, LedgerError.Code code) {
        return ledger.errors().stream().anyMatch(error -> error.code() == code);
    }

    private static long countType(Ledger ledger, Posting.Type type) {
        return ledger.postings().stream().filter(posting -> posting.type() == type).count();
    }

    @Test
    @DisplayName("approved when available balance lands exactly on zero, since the rule says at or above")
    void approvesAtExactlyZero() {
        Ledger ledger = replay(List.of(
                new LedgerEvent.Credit("C", 1, ACC, aed("100.00"), 1, 1),
                new LedgerEvent.Authorize("A", 1, ACC, "Auth-A", aed("100.00"), 1)), 2);

        assertEquals(AuthorizationTransition.State.APPROVED, ledger.stateOf("Auth-A").orElseThrow());
        assertEquals(Money.zero(AED), ledger.available(ACC, 1));
    }

    @Test
    void declinesWhenAvailableWouldGoBelowZero() {
        Ledger ledger = replay(List.of(
                new LedgerEvent.Credit("C", 1, ACC, aed("100.00"), 1, 1),
                new LedgerEvent.Authorize("A", 1, ACC, "Auth-A", aed("100.01"), 1)), 1);

        assertEquals(AuthorizationTransition.State.DECLINED, ledger.stateOf("Auth-A").orElseThrow());
        assertEquals(Money.zero(AED), ledger.activeHolds(ACC, 1));
        assertTrue(hasError(ledger, LedgerError.Code.AUTHORIZATION_DECLINED));
    }

    @Test
    @DisplayName("a declined authorization creates no hold and no posting")
    void aDeclineMovesNothing() {
        Ledger ledger = replay(List.of(
                new LedgerEvent.Authorize("A", 1, ACC, "Auth-A", aed("10.00"), 1)), 1);

        assertEquals(Money.zero(AED), ledger.balance(ACC, 1));
        assertTrue(ledger.postingsForEvent("A").isEmpty());
    }

    @Test
    void settlesAnOpenAuthorizationAndReleasesTheWholeHold() {
        Ledger ledger = replay(List.of(
                new LedgerEvent.Credit("C", 1, ACC, aed("650.00"), 1, 1),
                new LedgerEvent.Authorize("A", 1, ACC, "Auth-A", aed("200.00"), 1),
                new LedgerEvent.Settle("S", 2, ACC, "Auth-A", aed("185.00"), 2)), 3);

        assertEquals(aed("465.00"), ledger.balance(ACC, 2));
        assertEquals(Money.zero(AED), ledger.activeHolds(ACC, 2));
        assertEquals(AuthorizationTransition.State.SETTLED, ledger.stateOf("Auth-A").orElseThrow());
        assertEquals(aed("-185.00"), ledger.postingsForEvent("S").getFirst().amount());
    }

    @Test
    @DisplayName("the 15.00 left unused by a partial settlement returns to available, never to the ledger")
    void theUnusedPartOfAHoldIsFreedNotPosted() {
        Ledger ledger = replay(List.of(
                new LedgerEvent.Credit("C", 1, ACC, aed("650.00"), 1, 1),
                new LedgerEvent.Authorize("A", 1, ACC, "Auth-A", aed("200.00"), 1),
                new LedgerEvent.Settle("S", 2, ACC, "Auth-A", aed("185.00"), 2)), 3);

        assertEquals(aed("450.00"), ledger.available(ACC, 1));
        assertEquals(aed("465.00"), ledger.available(ACC, 2));
        assertEquals(1L, countType(ledger, Posting.Type.SETTLEMENT));
    }

    @Test
    void rejectsASettlementWithNoAuthorizationAndMovesNoMoney() {
        Ledger ledger = replay(List.of(
                new LedgerEvent.Credit("C", 1, ACC, aed("650.00"), 1, 1),
                new LedgerEvent.Settle("S", 1, ACC, "Auth-Z", aed("180.00"), 1)), 2);

        assertEquals(aed("650.00"), ledger.balance(ACC, 1));
        assertTrue(ledger.postingsForEvent("S").isEmpty());
        assertTrue(ledger.stateOf("Auth-Z").isEmpty());
        assertTrue(hasError(ledger, LedgerError.Code.ORPHAN_SETTLEMENT));
    }

    @Test
    void refusesToSettleTheSameAuthorizationTwice() {
        Ledger ledger = replay(List.of(
                new LedgerEvent.Credit("C", 1, ACC, aed("650.00"), 1, 1),
                new LedgerEvent.Authorize("A", 1, ACC, "Auth-A", aed("200.00"), 1),
                new LedgerEvent.Settle("S1", 2, ACC, "Auth-A", aed("100.00"), 2),
                new LedgerEvent.Settle("S2", 3, ACC, "Auth-A", aed("50.00"), 3)), 4);

        assertEquals(aed("550.00"), ledger.balance(ACC, 3));
        assertTrue(ledger.postingsForEvent("S2").isEmpty());
        assertTrue(hasError(ledger, LedgerError.Code.AUTHORIZATION_NOT_OPEN));
    }

    @Test
    @DisplayName("over-settlement posts in full and is flagged, because declining would hide a real overdraft")
    void overSettlementPostsInFull() {
        Ledger ledger = replay(List.of(
                new LedgerEvent.Credit("C", 1, ACC, aed("650.00"), 1, 1),
                new LedgerEvent.Authorize("A", 1, ACC, "Auth-A", aed("100.00"), 1),
                new LedgerEvent.Settle("S", 2, ACC, "Auth-A", aed("150.00"), 2)), 3);

        assertEquals(aed("500.00"), ledger.balance(ACC, 2));
        assertTrue(hasError(ledger, LedgerError.Code.OVER_SETTLEMENT));
    }

    @Test
    void reversalMirrorsTheTargetOnTheTargetValueDay() {
        Ledger ledger = replay(List.of(
                new LedgerEvent.Credit("C", 1, ACC, aed("1000.00"), 1, 1),
                new LedgerEvent.Debit("D", 2, ACC, aed("620.00"), 1),
                new LedgerEvent.Reversal("R", 3, ACC, "D", 1)), 4);

        assertEquals(aed("1000.00"), ledger.balance(ACC, 3, 3));
        assertEquals(aed("1000.00"), ledger.balance(ACC, 1));
        assertTrue(ledger.isReversed("D"));

        Posting mirror = ledger.postingsForEvent("R").getFirst();
        assertEquals(aed("620.00"), mirror.amount());
        assertEquals(1, mirror.valueDay());
        assertEquals(3, mirror.bookingDay());
    }

    @Test
    @DisplayName("nothing is deleted: the debit and its mirror both remain in the ledger")
    void reversalAddsRatherThanRemoves() {
        Ledger ledger = replay(List.of(
                new LedgerEvent.Debit("D", 1, ACC, aed("620.00"), 1),
                new LedgerEvent.Reversal("R", 2, ACC, "D", 1)), 2);

        assertEquals(1, ledger.postingsForEvent("D").size());
        assertEquals(1, ledger.postingsForEvent("R").size());
    }

    @Test
    void ignoresASecondReversalOfTheSameEvent() {
        Ledger ledger = replay(List.of(
                new LedgerEvent.Credit("C", 1, ACC, aed("1000.00"), 1, 1),
                new LedgerEvent.Debit("D", 1, ACC, aed("620.00"), 1),
                new LedgerEvent.Reversal("R1", 2, ACC, "D", 1),
                new LedgerEvent.Reversal("R2", 3, ACC, "D", 1)), 4);

        assertEquals(aed("1000.00"), ledger.balance(ACC, 3));
        assertTrue(ledger.postingsForEvent("R2").isEmpty());
        assertTrue(hasError(ledger, LedgerError.Code.DUPLICATE_REVERSAL));
    }

    @Test
    @DisplayName("reversing a reversal is blocked: a correction is not a general transaction")
    void blocksReversalOfAReversal() {
        Ledger ledger = replay(List.of(
                new LedgerEvent.Credit("C", 1, ACC, aed("1000.00"), 1, 1),
                new LedgerEvent.Debit("D", 1, ACC, aed("620.00"), 1),
                new LedgerEvent.Reversal("R1", 2, ACC, "D", 1),
                new LedgerEvent.Reversal("R2", 3, ACC, "R1", 1)), 4);

        assertEquals(aed("1000.00"), ledger.balance(ACC, 3));
        assertTrue(ledger.postingsForEvent("R2").isEmpty());
        assertTrue(hasError(ledger, LedgerError.Code.REVERSAL_OF_REVERSAL));
    }

    @Test
    void rejectsAReversalThatDisagreesWithItsTarget() {
        Ledger ledger = replay(List.of(
                new LedgerEvent.Debit("D", 1, ACC, aed("620.00"), 1),
                new LedgerEvent.Reversal("R", 2, ACC, "D", 2)), 2);

        assertEquals(aed("-620.00"), ledger.postingsForEvent("D").getFirst().amount());
        assertTrue(ledger.postingsForEvent("R").isEmpty());
        assertTrue(hasError(ledger, LedgerError.Code.REVERSAL_MISMATCH));
    }

    @Test
    void reportsAReversalWithNoTarget() {
        Ledger ledger = replay(List.of(
                new LedgerEvent.Reversal("R", 1, ACC, "NOPE", 1)), 1);

        assertTrue(hasError(ledger, LedgerError.Code.REVERSAL_TARGET_NOT_FOUND));
    }

    @Test
    void splitsACreditIntoEqualInstalmentsAndReportsTheDiscardedRemainder() {
        Ledger ledger = replayBhd(List.of(
                new LedgerEvent.Credit("E10", 1, BHD_ACC, bhd("10.000"), 1, 3)), 2,
                LedgerPolicy.defaults());

        List<Posting> parts = ledger.postingsForEvent("E10");
        assertEquals(3, parts.size());
        parts.forEach(part -> assertEquals(bhd("3.333"), part.amount()));
        assertEquals(bhd("9.999"), ledger.balance(BHD_ACC, 1));
        assertTrue(hasError(ledger, LedgerError.Code.INSTALMENT_RESIDUAL_DISCARDED));
    }

    @Test
    @DisplayName("giving the remainder to an instalment makes the split total exactly, and logs nothing")
    void residualCanBeGivenToAnInstalmentInstead() {
        LedgerPolicy toFirst = new LedgerPolicy(4L, 10_000L,
                LedgerPolicy.defaults().overdraftFee(),
                LedgerPolicy.ResidualPolicy.FIRST, false, false);

        Ledger ledger = replayBhd(List.of(
                new LedgerEvent.Credit("E10", 1, BHD_ACC, bhd("10.000"), 1, 3)), 2, toFirst);

        assertEquals(bhd("10.000"), ledger.balance(BHD_ACC, 1));
        assertEquals(bhd("3.334"), ledger.postingsForEvent("E10").getFirst().amount());
        assertTrue(ledger.errors().isEmpty());
    }

    @Test
    void assessesAtMostOneFeePerAccountPerDay() {
        Ledger ledger = replay(List.of(
                new LedgerEvent.Debit("D", 1, ACC, aed("100.00"), 1)), 2);

        assertEquals(1L, ledger.postings().stream()
                .filter(posting -> posting.type() == Posting.Type.OVERDRAFT_FEE)
                .filter(posting -> posting.valueDay() == 1)
                .count());
        assertEquals(aed("-125.00"), ledger.balance(ACC, 1));
        assertEquals(aed("-150.00"), ledger.balance(ACC, 2));
    }

    @Test
    @DisplayName("the fee is charged before interest, so a day it pushes negative earns nothing")
    void feeIsChargedBeforeInterest() {
        Ledger ledger = replay(List.of(
                new LedgerEvent.Credit("C", 1, ACC, aed("20.00"), 1, 1),
                new LedgerEvent.Debit("D", 1, ACC, aed("30.00"), 1)), 1);

        assertEquals(aed("-35.00"), ledger.balance(ACC, 1));
        assertEquals(Money.zero(AED), ledger.accrual(ACC, 1));
    }

    @Test
    @DisplayName("a BHD account cannot be charged an AED fee, so it is charged nothing and the gap is logged")
    void refusesToInventAFeeInAnotherCurrency() {
        Ledger ledger = replayBhd(List.of(
                new LedgerEvent.Debit("D", 1, BHD_ACC, bhd("5.000"), 1)), 1,
                LedgerPolicy.defaults());

        assertEquals(bhd("-5.000"), ledger.balance(BHD_ACC, 1));
        assertEquals(0L, countType(ledger, Posting.Type.OVERDRAFT_FEE));
        assertTrue(hasError(ledger, LedgerError.Code.FEE_CURRENCY_UNDEFINED));
    }

    @Test
    void storesAZeroAccrualForEveryDayOfTheWindow() {
        Ledger ledger = replay(List.of(
                new LedgerEvent.Credit("C", 1, ACC, aed("100.00"), 1, 1)), 4);

        assertEquals(4, ledger.accruals().size());
    }

    @Test
    void refusesAnEventBookedOutsideTheWindow() {
        LedgerEngine engine =
                new LedgerEngine(List.of(new Account(ACC, AED, Money.zero(AED))),
                        LedgerPolicy.defaults());
        List<LedgerEvent> events =
                List.of(new LedgerEvent.Credit("C", 9, ACC, aed("100.00"), 9, 1));

        assertThrows(IllegalArgumentException.class, () -> engine.replay(events, 1, 6));
    }

    @Test
    @DisplayName("the window is derived from the events when it is not given")
    void derivesTheWindowFromTheEvents() {
        Ledger ledger = new LedgerEngine(List.of(new Account(ACC, AED, Money.zero(AED))),
                LedgerPolicy.defaults())
                .replay(List.of(
                        new LedgerEvent.Credit("C", 1, ACC, aed("100.00"), 1, 1),
                        new LedgerEvent.Credit("C2", 3, ACC, aed("100.00"), 3, 1)));

        assertEquals(3, ledger.accruals().size());
        assertEquals(aed("100.00"), ledger.balance(ACC, 2, 2));
        assertTrue(ledger.capitalisationFor(ACC).isPresent());
    }

    @Test
    @DisplayName("events are processed in booking-day order even when the list is not")
    void sortsByBookingDayWhilePreservingListedOrder() {
        Ledger ledger = replay(List.of(
                new LedgerEvent.Credit("LATE", 3, ACC, aed("10.00"), 3, 1),
                new LedgerEvent.Credit("EARLY", 1, ACC, aed("100.00"), 1, 1)), 3);

        assertEquals(aed("100.00"), ledger.balance(ACC, 1));
        assertEquals(aed("0.04"), ledger.accrual(ACC, 1));
    }
}
