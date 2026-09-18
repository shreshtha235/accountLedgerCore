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

class LedgerTest {

    private static final String ACC = "ACC-001";

    private static Ledger ledger() {
        return new Ledger(List.of(new Account(ACC, AED, Money.zero(AED))), 1);
    }

    private static Money aed(String amount) {
        return Money.of(amount, AED);
    }

    @Test
    void seedsOneOpeningPostingPerAccount() {
        Ledger ledger = ledger();
        assertEquals(1, ledger.postings().size());
        assertEquals(Posting.Type.OPENING_BALANCE, ledger.postings().getFirst().type());
        assertEquals(Money.zero(AED), ledger.balance(ACC, 1));
    }

    @Test
    void balanceCountsOnlyWhatIsValueDatedOnOrBeforeTheCutoff() {
        Ledger ledger = ledger();
        ledger.post("E1", ACC, aed("100.00"), 1, 1, Posting.Type.CREDIT);
        ledger.post("E2", ACC, aed("50.00"), 3, 3, Posting.Type.CREDIT);

        assertEquals(aed("100.00"), ledger.balance(ACC, 2));
        assertEquals(aed("150.00"), ledger.balance(ACC, 3));
    }

    @Test
    @DisplayName("the bitemporal query hides entries booked after the as-at day")
    void balanceCanBeAskedAsAtAnEarlierBookingDay() {
        Ledger ledger = ledger();
        ledger.post("E1", ACC, aed("100.00"), 1, 1, Posting.Type.CREDIT);
        ledger.post("E7", ACC, aed("-620.00"), 2, 5, Posting.Type.DEBIT);

        assertEquals(aed("100.00"), ledger.balance(ACC, 2, 4));
        assertEquals(aed("-520.00"), ledger.balance(ACC, 2, 5));
    }

    @Test
    @DisplayName("a hold reduces available balance and never the ledger balance")
    void holdsStayOutsideTheLedger() {
        Ledger ledger = ledger();
        ledger.post("E1", ACC, aed("650.00"), 1, 1, Posting.Type.CREDIT);
        ledger.record(new AuthorizationTransition("Auth-A", ACC, aed("200.00"), 2,
                AuthorizationTransition.State.APPROVED, "hold placed"));

        assertEquals(aed("650.00"), ledger.balance(ACC, 2));
        assertEquals(aed("200.00"), ledger.activeHolds(ACC, 2));
        assertEquals(aed("450.00"), ledger.available(ACC, 2));
    }

    @Test
    void holdsAreEvaluatedAsAtTheGivenDay() {
        Ledger ledger = ledger();
        ledger.post("E1", ACC, aed("650.00"), 1, 1, Posting.Type.CREDIT);
        ledger.record(new AuthorizationTransition("Auth-A", ACC, aed("200.00"), 2,
                AuthorizationTransition.State.APPROVED, "hold placed"));
        ledger.record(new AuthorizationTransition("Auth-A", ACC, aed("200.00"), 4,
                AuthorizationTransition.State.SETTLED, "settled, hold released"));

        assertEquals(aed("200.00"), ledger.activeHolds(ACC, 3));
        assertEquals(Money.zero(AED), ledger.activeHolds(ACC, 4));
        assertEquals(AuthorizationTransition.State.SETTLED, ledger.stateOf("Auth-A").orElseThrow());
        assertTrue(ledger.holdOf("Auth-A").isEmpty());
    }

    @Test
    void everyStoreIsAppendOnly() {
        Ledger ledger = ledger();
        Posting opening = ledger.postings().getFirst();
        assertThrows(UnsupportedOperationException.class, () -> ledger.postings().add(opening));
        assertThrows(UnsupportedOperationException.class, () -> ledger.accruals().clear());
        assertThrows(UnsupportedOperationException.class, () -> ledger.transitions().clear());
        assertThrows(UnsupportedOperationException.class, () -> ledger.errors().clear());
    }

    @Test
    void refusesAnAmountInTheWrongCurrency() {
        Ledger ledger = ledger();
        Money dinars = Money.of("1.000", BHD);
        assertThrows(IllegalArgumentException.class,
                () -> ledger.post("X", ACC, dinars, 1, 1, Posting.Type.CREDIT));
    }

    @Test
    void refusesAnUnknownAccount() {
        Ledger ledger = ledger();
        assertThrows(IllegalArgumentException.class, () -> ledger.balance("NOPE", 1));
    }

    @Test
    void refusesDuplicateAccountIds() {
        Account first = new Account(ACC, AED, Money.zero(AED));
        Account second = new Account(ACC, AED, Money.zero(AED));
        assertThrows(IllegalArgumentException.class, () -> new Ledger(List.of(first, second)));
    }

    @Test
    @DisplayName("a later accrual row for the same day supersedes the earlier one")
    void accrualTakesTheLatestRowPerDay() {
        Ledger ledger = ledger();
        ledger.record(new Accrual(ACC, 1, aed("0.10")));
        ledger.record(new Accrual(ACC, 1, aed("0.05")));
        ledger.record(new Accrual(ACC, 2, aed("0.20")));

        assertEquals(aed("0.05"), ledger.accrual(ACC, 1));
        assertEquals(aed("0.25"), ledger.accrualTotal(ACC));
    }

    @Test
    void tracksWhetherAnEventHasBeenReversed() {
        Ledger ledger = ledger();
        ledger.post("E7", ACC, aed("-620.00"), 2, 5, Posting.Type.DEBIT);
        assertFalse(ledger.isReversed("E7"));

        ledger.postReversal("E9", ACC, aed("620.00"), 2, 6, "E7");
        assertTrue(ledger.isReversed("E7"));
        assertEquals(Money.zero(AED), ledger.balance(ACC, 2));
    }

    @Test
    void findsEveryPostingAnEventProduced() {
        Ledger ledger = ledger();
        ledger.post("E10", ACC, aed("3.33"), 5, 5, Posting.Type.CREDIT);
        ledger.post("E10", ACC, aed("3.33"), 5, 5, Posting.Type.CREDIT);
        ledger.post("E10", ACC, aed("3.33"), 5, 5, Posting.Type.CREDIT);

        assertEquals(3, ledger.postingsForEvent("E10").size());
    }
}
