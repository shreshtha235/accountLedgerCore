package com.ledger;

import static com.ledger.CurrencyCode.AED;
import static com.ledger.CurrencyCode.BHD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The eight stated acceptance criteria. Four are refused; each of those asserts the value this
 * design holds to be correct and says inline why the stated criterion fails. See REJECTED.md.
 */
class AcceptanceCriteriaTest {

    private static Ledger ledger;

    @BeforeAll
    static void replayTheStream() {
        ledger = new LedgerEngine(EventStream.accounts(), LedgerPolicy.defaults())
                .replay(EventStream.events(), EventStream.FIRST_DAY, EventStream.LAST_DAY);
    }

    private static Money aed(String amount) {
        return Money.of(amount, AED);
    }

    private static Money bhd(String amount) {
        return Money.of(amount, BHD);
    }

    private static long feeCount() {
        return ledger.postings().stream()
                .filter(posting -> posting.type() == Posting.Type.OVERDRAFT_FEE)
                .count();
    }

    @Test
    @DisplayName("1 ACCEPTED - Day 2 at end of Day 5, before any fee, is AED -370.00")
    void criterion1() {
        // 1,200.00 - 950.00 - 620.00. E9 has not arrived and Auth-A's hold is not a ledger entry.
        // True under either reading of the fee rule, which makes it the only reading-independent
        // criterion of the eight.
        assertEquals(aed("-370.00"), ledger.balance(EventStream.ACC_001, 2, 5));
    }

    @Test
    @DisplayName("2 REFUSED - E7 causes exactly one fee, but on Day 5, not Day 2")
    void criterion2() {
        // The count is right and the day is wrong. E7 arrives on Day 5, so Day 5 is the day
        // assessed and the fee is value-dated Day 5. Day 2 was assessed on Day 2, when it closed
        // at 250.00. The two halves of the stated criterion cannot both hold: under a
        // forward-only reading there is exactly one fee but it sits on Day 5, and under a
        // reopen-history reading Day 2 does take a fee but so do Day 4 and Day 5, making three.
        assertEquals(1L, feeCount());
        assertTrue(ledger.feeOn(EventStream.ACC_001, 5).isPresent());
        assertTrue(ledger.feeOn(EventStream.ACC_001, 2).isEmpty());
        assertEquals(aed("-25.00"), ledger.feeOn(EventStream.ACC_001, 5).orElseThrow());

        Ledger reopened = new LedgerEngine(EventStream.accounts(),
                LedgerPolicy.defaults().withReopenClosedDays(true))
                .replay(EventStream.events(), EventStream.FIRST_DAY, EventStream.LAST_DAY);
        long reopenedFees = reopened.postings().stream()
                .filter(posting -> posting.type() == Posting.Type.OVERDRAFT_FEE)
                .count();
        assertEquals(3L, reopenedFees);
    }

    @Test
    @DisplayName("3 ACCEPTED - the Day 4 settlement of Auth-A is accepted")
    void criterion3() {
        // Auth-A was approved on Day 2 and settles for 185.00 against a 200.00 hold, so it is
        // within what was reserved. Honouring a commitment already made is not re-tested against
        // the balance.
        assertEquals(AuthorizationTransition.State.SETTLED,
                ledger.stateOf("Auth-A").orElseThrow());
        assertEquals(aed("-185.00"), ledger.postingsForEvent("E5").getFirst().amount());
        assertEquals(4, ledger.postingsForEvent("E5").getFirst().valueDay());
    }

    @Test
    @DisplayName("4 REFUSED AS WORDED - the lookup is the authorization register, not the ledger")
    void criterion4() {
        // The behaviour is right: the orphan settlement is rejected and no money moves.
        assertTrue(ledger.postingsForEvent("E6").isEmpty());
        assertTrue(ledger.stateOf("Auth-Z").isEmpty());
        assertTrue(ledger.errors().stream()
                .anyMatch(error -> error.code() == LedgerError.Code.ORPHAN_SETTLEMENT));

        // The wording is wrong. It says "an authorization ID not present in the ledger", but an
        // authorization is never a ledger entry at all - E3 approved Auth-A and produced no
        // posting. Read literally, no authorization is ever present in the ledger, so every
        // settlement would be rejected, including Auth-A's, which contradicts criterion 3.
        assertTrue(ledger.postingsForEvent("E3").isEmpty());
    }

    @Test
    @DisplayName("5 ACCEPTED but unexercised - Auth-B is declined, so the condition never holds")
    void criterion5() {
        // The statement is correct and is carefully conditional. In this stream the condition is
        // never met: by the time Auth-B arrives, E7 has landed and the ledger is already -155.00,
        // below zero before the 90.00 hold is applied.
        assertEquals(AuthorizationTransition.State.DECLINED,
                ledger.stateOf("Auth-B").orElseThrow());
        assertEquals(Money.zero(AED), ledger.activeHolds(EventStream.ACC_001, 6));

        // The rule itself holds where it is exercised - Auth-A's hold on Day 3 reduced available
        // balance by 200.00 and left the ledger balance untouched.
        assertEquals(aed("650.00"), ledger.balance(EventStream.ACC_001, 3, 3));
        assertEquals(aed("200.00"), ledger.activeHolds(EventStream.ACC_001, 3));
        assertEquals(aed("450.00"), ledger.available(EventStream.ACC_001, 3));
    }

    @Test
    @DisplayName("6 REFUSED - after E9 nothing returns to its pre-E7 value, and a fee never can")
    void criterion6() {
        List<LedgerEvent> withoutE7 = EventStream.events().stream()
                .filter(event -> !event.id().equals("E7") && !event.id().equals("E9"))
                .toList();
        Ledger counterfactual = new LedgerEngine(EventStream.accounts(), LedgerPolicy.defaults())
                .replay(withoutE7, EventStream.FIRST_DAY, EventStream.LAST_DAY);

        // Had E7 never happened the account would close at 466.03. With E7 and its reversal it
        // closes at 440.83. The two 620s cancel; the 25.00 fee does not, and neither does the
        // interest those days failed to earn.
        assertEquals(aed("466.03"), counterfactual.balance(EventStream.ACC_001, 6));
        assertEquals(aed("440.83"), ledger.balance(EventStream.ACC_001, 6));
        assertNotEquals(counterfactual.balance(EventStream.ACC_001, 6),
                ledger.balance(EventStream.ACC_001, 6));

        // And "fees return to their pre-E7 values" is impossible by construction. The ledger is
        // append-only, so an assessed fee can only ever be offset by a further entry, never
        // un-assessed. Both the debit and its mirror are still there, and so is the fee.
        assertEquals(1, ledger.postingsForEvent("E7").size());
        assertEquals(1, ledger.postingsForEvent("E9").size());
        assertEquals(1L, feeCount());
        assertEquals(0L, counterfactual.postings().stream()
                .filter(posting -> posting.type() == Posting.Type.OVERDRAFT_FEE)
                .count());
    }

    @Test
    @DisplayName("7 REFUSED - three instalments of 3.334 would credit 10.002 against a 10.000 event")
    void criterion7() {
        List<Posting> instalments = ledger.postingsForEvent("E10");
        assertEquals(3, instalments.size());
        instalments.forEach(part -> assertEquals(bhd("3.333"), part.amount()));

        // 3.334 three times is 10.002, which is 0.002 more than the event credited. That is
        // inventing money. The only defensible splits are 3.333 three times, which falls 0.001
        // short and is the ruling in force, or 3.334 plus 3.333 plus 3.333, which totals exactly.
        assertEquals(bhd("10.002"),
                bhd("3.334").plus(bhd("3.334")).plus(bhd("3.334")));
        assertNotEquals(bhd("10.000"), bhd("3.334").plus(bhd("3.334")).plus(bhd("3.334")));
        assertEquals(bhd("9.999"), ledger.balance(EventStream.ACC_002, 5, 5));
    }

    @Test
    @DisplayName("8 REFUSED - the capitalised total is defined as the sum, so no remainder exists")
    void criterion8() {
        Money capitalised = ledger.capitalisationFor(EventStream.ACC_001).orElseThrow();

        // The non-negotiable rule says the rounded daily accruals must sum exactly to the
        // capitalised total. That is satisfied by defining the capitalised figure as that sum, so
        // a remainder cannot arise and there is nothing to discard.
        assertEquals(ledger.accrualTotal(EventStream.ACC_001), capitalised);
        assertEquals(aed("0.83"), capitalised);

        // The criterion instead permits a mismatch and then throws the difference away. Applying
        // the rate once to the total of the accrual bases gives 0.82, a fil less. Under the
        // stated criterion that fil would be silently lost and the account's interest would no
        // longer reconcile against its own accrual rows.
        Money basis = Money.zero(AED);
        for (int day = EventStream.FIRST_DAY; day <= EventStream.LAST_DAY; day++) {
            Money atClose = ledger.balance(EventStream.ACC_001, day, day);
            if (day == EventStream.LAST_DAY) {
                atClose = atClose.minus(capitalised);
            }
            if (atClose.isPositive()) {
                basis = basis.plus(atClose);
            }
        }
        assertEquals(aed("2055.00"), basis);
        assertEquals(aed("0.82"), basis.applyRate(4L, 10_000L));
        assertNotEquals(basis.applyRate(4L, 10_000L), capitalised);
    }
}
