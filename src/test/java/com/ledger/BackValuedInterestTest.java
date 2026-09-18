package com.ledger;

import static com.ledger.CurrencyCode.AED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * THE DELIBERATELY FAILING TEST.
 *
 * <p>Run it with: mvn test -Dgroups=failing
 *
 * <p>It is tagged and excluded from the default suite so the build stays green while the gap
 * stays visible and runnable. It is not a defect in the code — it is the edge of the model this
 * design chose, and it fails by AED 0.48.
 */
@Tag("failing")
class BackValuedInterestTest {

    @Test
    @DisplayName("capitalised interest should match the corrected history after a late reversal")
    void capitalisedInterestSurvivesALateCorrection() {
        // WHAT THIS REVEALS
        //
        // The design assesses each day once, at its close, and capitalises the sum of the stored
        // accruals on the last day of the window. That is coherent and simple, and it cannot
        // survive a correction that arrives after capitalisation.
        //
        // Suppose on Day 7 a reversal arrives cancelling E4, the 400.00 credit value-dated Day 3.
        // Every day from Day 3 onward was then 400.00 higher than it should have been, so four
        // days of accrual are wrong - not one. The corrected history earns 0.35. The ledger has
        // already paid 0.83 and has no path to revisit it.
        //
        // Note the direction: here the bank has over-paid, so putting it right means taking money
        // back off the customer, which is far more sensitive than paying a little more. And
        // because the 0.83 has capitalised, it is now part of the balance and is itself earning
        // interest, so the error grows rather than sitting still.
        //
        // WHY THIS IS NOT A BUG TO FIX HERE
        //
        // Fixing it needs a back-valued interest adjustment: recompute the corrected accruals,
        // post the difference as a new entry on the day the correction arrives, and stop at a
        // cutoff beyond which late corrections are treated as today's business instead of
        // reopening a closed period. Every part of that is a policy decision, not a code change.
        // The cutoff in particular is the one control I would insist on before go-live.
        //
        // This test is only possible because accruals are stored per day rather than as a running
        // total. Had the design kept only the total, the gap could not even be measured.

        Ledger asRun = new LedgerEngine(EventStream.accounts(), LedgerPolicy.defaults())
                .replay(EventStream.events(), EventStream.FIRST_DAY, EventStream.LAST_DAY);

        List<LedgerEvent> corrected = EventStream.events().stream()
                .filter(event -> !event.id().equals("E4"))
                .toList();
        Ledger asItShouldHaveBeen = new LedgerEngine(EventStream.accounts(),
                LedgerPolicy.defaults())
                .replay(corrected, EventStream.FIRST_DAY, EventStream.LAST_DAY);

        Money paid = asRun.capitalisationFor(EventStream.ACC_001).orElseThrow();
        Money earned = asItShouldHaveBeen.capitalisationFor(EventStream.ACC_001).orElseThrow();

        assertEquals(Money.of("0.83", AED), paid, "interest the ledger actually capitalised");
        assertEquals(Money.of("0.35", AED), earned, "interest the corrected history earned");

        // Fails by 0.48. There is no mechanism in this design to close that gap.
        assertEquals(earned, paid,
                "capitalised interest must match the corrected history, over-paid by "
                        + paid.minus(earned).format());
    }
}
