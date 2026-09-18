package com.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InvariantTest {

    private static Ledger replay(LedgerPolicy policy) {
        return new LedgerEngine(EventStream.accounts(), policy)
                .replay(EventStream.events(), EventStream.FIRST_DAY, EventStream.LAST_DAY);
    }

    private static List<LedgerPolicy> bothReadings() {
        return List.of(LedgerPolicy.defaults(),
                LedgerPolicy.defaults().withReopenClosedDays(true));
    }

    @Test
    @DisplayName("the rounded daily accruals sum exactly to the capitalised credit, under either reading")
    void accrualsSumToTheCapitalisedCredit() {
        for (LedgerPolicy policy : bothReadings()) {
            Ledger ledger = replay(policy);
            for (Account account : ledger.accounts()) {
                Money total = ledger.accrualTotal(account.id());
                Money capitalised = ledger.capitalisationFor(account.id())
                        .orElse(Money.zero(account.currency()));
                assertEquals(total, capitalised,
                        account.id() + " accrual sum must equal its capitalised credit");
            }
        }
    }

    @Test
    @DisplayName("the accrual log holds exactly one row per account per day of the window")
    void oneAccrualRowPerAccountPerDay() {
        Ledger ledger = replay(LedgerPolicy.defaults());
        int days = EventStream.LAST_DAY - EventStream.FIRST_DAY + 1;
        assertEquals(ledger.accounts().size() * days, ledger.accruals().size());
    }

    @Test
    void atMostOneOverdraftFeePerAccountPerDay() {
        for (LedgerPolicy policy : bothReadings()) {
            Ledger ledger = replay(policy);
            Set<String> seen = new HashSet<>();
            for (Posting posting : ledger.postings()) {
                if (posting.type() != Posting.Type.OVERDRAFT_FEE) {
                    continue;
                }
                String key = posting.accountId() + "#" + posting.valueDay();
                assertTrue(seen.add(key), "a second fee was assessed for " + key);
            }
        }
    }

    @Test
    @DisplayName("posting sequence numbers are unique and gapless, so nothing was rewritten")
    void postingsAreAppendedInOrder() {
        Ledger ledger = replay(LedgerPolicy.defaults());
        List<Posting> postings = ledger.postings();
        for (int index = 0; index < postings.size(); index++) {
            assertEquals(index, postings.get(index).seq());
        }
    }

    @Test
    @DisplayName("nothing reads a clock or a random source, so two replays are identical")
    void replayIsDeterministic() {
        Ledger first = replay(LedgerPolicy.defaults());
        Ledger second = replay(LedgerPolicy.defaults());

        assertEquals(first.postings(), second.postings());
        assertEquals(first.transitions(), second.transitions());
        assertEquals(first.accruals(), second.accruals());
        assertEquals(first.errors(), second.errors());
    }

    @Test
    @DisplayName("every posting is in its own account's currency")
    void noCrossCurrencyPostingSurvives() {
        Ledger ledger = replay(LedgerPolicy.defaults());
        for (Posting posting : ledger.postings()) {
            assertEquals(ledger.currencyOf(posting.accountId()), posting.amount().currency());
        }
    }

    @Test
    @DisplayName("MEASURED SHORTFALL - the instalments of E10 total 0.001 less than the event credited")
    void instalmentsDoNotSumToTheCreditedAmount() {
        // This invariant is deliberately broken by the ruling to discard the residual, and is
        // reported rather than skipped. The same brief requires the rounded interest accruals to
        // sum exactly to the capitalised total - a strict no-loss rule for the identical kind of
        // arithmetic - so the two instructions are inconsistent. The consequence is that the
        // ledger holds 9.999 against an event stating 10.000.
        Ledger ledger = replay(LedgerPolicy.defaults());

        Money credited = Money.of("10.000", CurrencyCode.BHD);
        Money posted = Money.zero(CurrencyCode.BHD);
        for (Posting posting : ledger.postingsForEvent("E10")) {
            posted = posted.plus(posting.amount());
        }

        Money shortfall = credited.minus(posted);
        assertEquals(Money.ofMinor(1L, CurrencyCode.BHD), shortfall,
                "the discarded residual should be exactly 0.001");
        assertTrue(ledger.errors().stream()
                        .anyMatch(error ->
                                error.code() == LedgerError.Code.INSTALMENT_RESIDUAL_DISCARDED),
                "a discarded residual must never be silent");
    }
}
