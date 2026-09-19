# Rejected

## Acceptance criteria

| Criterion | Verdict | Reason |
| --- | --- | --- |
| Day 2 closing balance evaluated at end of Day 5 is AED −370.00 | Accepted | 1,200 − 950 − 620 = −370. Correct. |
| E7 causes exactly one overdraft fee, on Day 2 | Rejected | Both parts cannot be true at the same time. If days are never reopened, fee is on Day 5 not Day 2. If days are reopened, three fees are charged not one. |
| Day 4 settlement of Auth-A must be accepted | Accepted | Auth-A held 200.00, E5 settles for 185.00 which is within the hold. Correct. |
| Settlement referencing an unknown authorization must be rejected, funds must not move | Accepted (behaviour) / Rejected (wording) | E6 is correctly rejected and ORPHAN_SETTLEMENT is logged. But the wording says "not in the ledger" — authorizations are never in the ledger, so read literally every settlement would be rejected. |
| If Auth-B is approved, its hold reduces available balance not ledger balance | Accepted (rule) / Does not apply (stream) | The rule is correct. Auth-B is declined in this stream so this path is never reached. |
| After E9, all balances and fees return to pre-E7 values | Rejected | E7 and E9 cancel each other but the Day 5 fee stays. Also the ledger is append-only — nothing can be "returned" to a previous value, only offset by a new entry. |
| The three BHD instalments must each be BHD 3.334 | Rejected | 3.334 × 3 = 10.002, which is more than the 10.000 that was credited. Correct split is 3.333 × 3 = 9.999, residual 0.001 is discarded. |
| If rounded daily accruals do not sum to the capitalised total, discard the remainder | Rejected | The capitalised total is defined as the sum of the stored rounded accruals, so a mismatch means a bug not a rounding gap. Discarding it would hide the bug. |

## Approaches abandoned mid-build

| Approach | Why dropped |
| --- | --- |
| Reopen old days for fee recalculation by default | Nothing in the brief asks for it. Kept as a flag `reopenClosedDaysForFeesCalculation = false` so both readings can be compared. |
| Force-posting E6 (orphan settlement) | Told to reject it. Corrected. |
| 12 configurable policy fields | Most values never changed. Non-varying ones are now constants, policy kept only the genuinely uncertain ones. |
| `Comparable<Money>` | Nothing ever compared two amounts directly. Removed. |
| Running balance per account | A backdated entry invalidates any stored balance. Balances are recomputed from postings every time. |
| Floating point for interest | 0.1 cannot be represented exactly in binary. Fine over 6 days, wrong over years. |
| Removing the fee when E9 arrives | The ledger is append-only, nothing can be removed. A compensating credit is the right approach. |
