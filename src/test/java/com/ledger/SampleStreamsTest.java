package com.ledger;

import static com.ledger.CurrencyCode.AED;
import static com.ledger.CurrencyCode.BHD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Five complete event streams with 10-15 events each, each testing a distinct real-world
 * scenario end to end across multiple days, accounts, and edge cases.
 */
class SampleStreamsTest {

    private static final String A1 = "ACC-1";
    private static final String A2 = "ACC-2";

    private static final List<Account> TWO_ACCOUNTS = List.of(
            new Account(A1, AED, Money.zero(AED)),
            new Account(A2, AED, Money.zero(AED))
    );

    private static final List<Account> AED_AND_BHD = List.of(
            new Account(A1, AED, Money.zero(AED)),
            new Account(A2, BHD, Money.zero(BHD))
    );

    private static Money aed(String v) { return Money.of(v, AED); }
    private static Money bhd(String v) { return Money.of(v, BHD); }

    @Test
    @DisplayName("Stream 1 — full merchant lifecycle: credits, debits, auths, settle, reversal, interest")
    void fullMerchantLifecycle() {
        // Day 1: salary credit 5000, small debit 200
        // Day 2: auth 800 (hotel), auth 100 (petrol), debit 300 (groceries)
        // Day 3: settle hotel for 750, debit 500 (rent)
        // Day 4: petrol settles for 95, credit 200 (refund)
        // Day 5: debit 1000, debit 800
        // Day 6: reversal of Day5 1000 debit — account was fine so no fee
        // Day 7: capitalise
        List<LedgerEvent> events = List.of(
                new LedgerEvent.Credit("E1",  1, A1, aed("5000.00"), 1, 1),
                new LedgerEvent.Debit("E2",   1, A1, aed("200.00"),  1),
                new LedgerEvent.Authorize("E3", 2, A1, "Hotel",  aed("800.00"), 2),
                new LedgerEvent.Authorize("E4", 2, A1, "Petrol", aed("100.00"), 2),
                new LedgerEvent.Debit("E5",   2, A1, aed("300.00"),  2),
                new LedgerEvent.Settle("E6",  3, A1, "Hotel",  aed("750.00"), 3),
                new LedgerEvent.Debit("E7",   3, A1, aed("500.00"),  3),
                new LedgerEvent.Settle("E8",  4, A1, "Petrol", aed("95.00"),  4),
                new LedgerEvent.Credit("E9",  4, A1, aed("200.00"),  4, 1),
                new LedgerEvent.Debit("E10",  5, A1, aed("1000.00"), 5),
                new LedgerEvent.Debit("E11",  5, A1, aed("800.00"),  5),
                new LedgerEvent.Reversal("E12", 6, A1, "E10", 5)
        );
        Ledger ledger = new LedgerEngine(
                List.of(new Account(A1, AED, Money.zero(AED))),
                LedgerPolicy.defaults()).replay(events, 1, 7);

        // Day 2: ledger 4500, holds 900 (hotel+petrol), available 3600
        assertEquals(aed("4500.00"), ledger.balance(A1, 2, 2));
        assertEquals(aed("900.00"),  ledger.activeHolds(A1, 2));
        assertEquals(aed("3600.00"), ledger.available(A1, 2));

        // Day 3: hotel settled -750, debit -500 → ledger = 4500 - 750 - 500 = 3250, petrol hold 100
        assertEquals(aed("3250.00"), ledger.balance(A1, 3, 3));
        assertEquals(aed("100.00"),  ledger.activeHolds(A1, 3));

        // Day 5: ledger = 3250 - 95 + 200 - 1000 - 800 = 1555; reversal not yet
        assertEquals(aed("1555.00"), ledger.balance(A1, 5, 5));

        // Day 6: reversal of E10 (+1000 back) → restated Day 5 = 2555
        assertEquals(aed("2555.00"), ledger.balance(A1, 5));

        // No overdraft, no fees
        for (int day = 1; day <= 7; day++) {
            assertTrue(ledger.feeOn(A1, day).isEmpty(), "no fee on day " + day);
        }

        // No errors
        assertTrue(ledger.errors().isEmpty());

        // Accruals sum equals capitalised
        assertEquals(ledger.accrualTotal(A1), ledger.capitalisationFor(A1).orElseThrow());
    }

    @Test
    @DisplayName("Stream 2 — overdraft cascade: multiple debits drive account negative across days, fee each day")
    void overdraftCascade() {
        // Day 1: credit 300
        // Day 2: debit 200 → 100 left
        // Day 3: debit 150 → -50, fee -25 → -75
        // Day 4: debit 50  → -125, fee -25 → -150
        // Day 5: debit 30  → -180, fee -25 → -205
        // Day 6: big credit 1000 recovers account
        // Day 7: no fee
        List<LedgerEvent> events = List.of(
                new LedgerEvent.Credit("E1", 1, A1, aed("300.00"), 1, 1),
                new LedgerEvent.Debit("E2",  2, A1, aed("200.00"), 2),
                new LedgerEvent.Debit("E3",  3, A1, aed("150.00"), 3),
                new LedgerEvent.Debit("E4",  4, A1, aed("50.00"),  4),
                new LedgerEvent.Debit("E5",  5, A1, aed("30.00"),  5),
                new LedgerEvent.Credit("E6", 6, A1, aed("1000.00"), 6, 1)
        );
        Ledger ledger = new LedgerEngine(
                List.of(new Account(A1, AED, Money.zero(AED))),
                LedgerPolicy.defaults()).replay(events, 1, 7);

        assertEquals(aed("100.00"),  ledger.balance(A1, 2, 2));
        assertTrue(ledger.feeOn(A1, 2).isEmpty());

        assertEquals(aed("-75.00"),  ledger.balance(A1, 3, 3));
        assertTrue(ledger.feeOn(A1, 3).isPresent());

        assertEquals(aed("-150.00"), ledger.balance(A1, 4, 4));
        assertTrue(ledger.feeOn(A1, 4).isPresent());

        assertEquals(aed("-205.00"), ledger.balance(A1, 5, 5));
        assertTrue(ledger.feeOn(A1, 5).isPresent());

        // Day 6: -205 + 1000 = 795
        assertEquals(aed("795.00"), ledger.balance(A1, 6, 6));
        assertTrue(ledger.feeOn(A1, 6).isEmpty());
        assertTrue(ledger.feeOn(A1, 7).isEmpty());
    }

    @Test
    @DisplayName("Stream 3 — authorization edge cases: orphan, over-settlement, duplicate attempt")
    void authorizationEdgeCases() {
        // Day 1: credit 2000
        // Day 2: auth-A 500, auth-B 300
        // Day 3: settle auth-X (orphan — never authorized)
        // Day 4: settle auth-A for 600 (over-settlement)
        // Day 5: settle auth-A again (already closed)
        // Day 6: settle auth-B for 280
        List<LedgerEvent> events = List.of(
                new LedgerEvent.Credit("E1",  1, A1, aed("2000.00"), 1, 1),
                new LedgerEvent.Authorize("E2", 2, A1, "Auth-A", aed("500.00"), 2),
                new LedgerEvent.Authorize("E3", 2, A1, "Auth-B", aed("300.00"), 2),
                new LedgerEvent.Settle("E4",  3, A1, "Auth-X", aed("400.00"), 3),
                new LedgerEvent.Settle("E5",  4, A1, "Auth-A", aed("600.00"), 4),
                new LedgerEvent.Settle("E6",  5, A1, "Auth-A", aed("100.00"), 5),
                new LedgerEvent.Settle("E7",  6, A1, "Auth-B", aed("280.00"), 6)
        );
        Ledger ledger = new LedgerEngine(
                List.of(new Account(A1, AED, Money.zero(AED))),
                LedgerPolicy.defaults()).replay(events, 1, 7);

        // Day 3: orphan settlement logged, nothing moved
        assertEquals(1, ledger.errorsOn(3).size());
        assertEquals(LedgerError.Code.ORPHAN_SETTLEMENT, ledger.errorsOn(3).get(0).code());
        assertEquals(aed("2000.00"), ledger.balance(A1, 3, 3));

        // Day 4: over-settlement — 600 posted even though hold was 500
        assertEquals(1, ledger.errorsOn(4).size());
        assertEquals(LedgerError.Code.OVER_SETTLEMENT, ledger.errorsOn(4).get(0).code());
        assertEquals(aed("1400.00"), ledger.balance(A1, 4, 4));

        // Day 5: second settle attempt on already-closed auth
        assertEquals(1, ledger.errorsOn(5).size());
        assertEquals(LedgerError.Code.AUTHORIZATION_NOT_OPEN, ledger.errorsOn(5).get(0).code());

        // Day 6: auth-B settles cleanly
        assertEquals(aed("1120.00"), ledger.balance(A1, 6, 6));
        assertTrue(ledger.errorsOn(6).isEmpty());

        // Auth-B hold gone after settlement
        assertEquals(aed("0.00"), ledger.activeHolds(A1, 6));
    }

    @Test
    @DisplayName("Stream 4 — two accounts, one goes overdraft, BHD account has no fee defined")
    void twoAccountsOneAedOneBhd() {
        // AED account: credit 500, debit 600 → overdraft, fee
        // BHD account: credit 10.000, debit 12.000 → overdraft but no BHD fee defined
        List<LedgerEvent> events = List.of(
                new LedgerEvent.Credit("E1", 1, A1, aed("500.00"), 1, 1),
                new LedgerEvent.Credit("E2", 1, A2, bhd("10.000"), 1, 1),
                new LedgerEvent.Debit("E3",  2, A1, aed("600.00"), 2),
                new LedgerEvent.Debit("E4",  2, A2, bhd("12.000"), 2),
                new LedgerEvent.Credit("E5", 3, A1, aed("200.00"), 3, 1),
                new LedgerEvent.Credit("E6", 3, A2, bhd("5.000"),  3, 1),
                new LedgerEvent.Debit("E7",  4, A1, aed("100.00"), 4),
                new LedgerEvent.Debit("E8",  4, A2, bhd("1.000"),  4),
                new LedgerEvent.Credit("E9", 5, A1, aed("50.00"),  5, 1),
                new LedgerEvent.Credit("E10",5, A2, bhd("2.000"),  5, 1)
        );
        Ledger ledger = new LedgerEngine(AED_AND_BHD, LedgerPolicy.defaults())
                .replay(events, 1, 6);

        // AED account overdraft on Day 2: 500-600 = -100, fee -25 → -125
        assertEquals(aed("-125.00"), ledger.balance(A1, 2, 2));
        assertTrue(ledger.feeOn(A1, 2).isPresent());

        // BHD account overdraft on Day 2: 10-12 = -2, no fee defined
        assertEquals(bhd("-2.000"), ledger.balance(A2, 2, 2));
        assertTrue(ledger.feeOn(A2, 2).isEmpty());

        // BHD overdraft logs FEE_CURRENCY_UNDEFINED
        assertTrue(ledger.errorsOn(2).stream()
                .anyMatch(e -> e.code() == LedgerError.Code.FEE_CURRENCY_UNDEFINED));

        // Both accrual invariants hold
        assertEquals(ledger.accrualTotal(A1), ledger.capitalisationFor(A1).orElseThrow());
        assertEquals(ledger.accrualTotal(A2), ledger.capitalisationFor(A2).orElseThrow());
    }

    @Test
    @DisplayName("Stream 5 — backdated entries across multiple days, fees land on arrival not value day")
    void multipleBackdatedEntries() {
        // Day 1: credit 1000
        // Day 3: debit 1200 backdated to Day 1 → Day 1 was positive when it closed, fee on Day 3
        // Day 4: credit 500 backdated to Day 2
        // Day 5: debit 300 backdated to Day 2
        // Day 6: reversal of Day3 debit (backdated to Day 1)
        // Day 7: capitalise
        List<LedgerEvent> events = List.of(
                new LedgerEvent.Credit("E1",  1, A1, aed("1000.00"), 1, 1),
                new LedgerEvent.Debit("E2",   3, A1, aed("1200.00"), 1),
                new LedgerEvent.Credit("E3",  4, A1, aed("500.00"),  2, 1),
                new LedgerEvent.Debit("E4",   5, A1, aed("300.00"),  2),
                new LedgerEvent.Reversal("E5", 6, A1, "E2", 1),
                new LedgerEvent.Credit("E6",  6, A1, aed("100.00"),  6, 1)
        );
        Ledger ledger = new LedgerEngine(
                List.of(new Account(A1, AED, Money.zero(AED))),
                LedgerPolicy.defaults()).replay(events, 1, 7);

        // Day 1 as at Day 1: just 1000
        assertEquals(aed("1000.00"), ledger.balance(A1, 1, 1));

        // Day 1 as known by Day 5 (after debit E2 arrived, before reversal): 1000 - 1200 = -200
        assertEquals(aed("-200.00"), ledger.balance(A1, 1, 5));

        // Fee on Day 3 (arrival of E2), not Day 1
        assertTrue(ledger.feeOn(A1, 1).isEmpty());
        assertTrue(ledger.feeOn(A1, 3).isPresent());

        // Day 1 final restated (after reversal on Day 6): back to 1000
        assertEquals(aed("1000.00"), ledger.balance(A1, 1));

        // Accrual invariant holds
        assertEquals(ledger.accrualTotal(A1), ledger.capitalisationFor(A1).orElseThrow());
    }
}
