# Ambiguities

Every ambiguity found in the brief, how it was resolved, and what would change if the
resolution were reversed. Written as the work happened, so entries appear in the order they
were discovered rather than in order of importance.

Status key: **ruled** — decided by the interviewer; **chosen** — defaulted by me and
documented; **open** — still unanswered, a default is in place.

## The ones that move the final numbers

| # | Ambiguity | Resolution | If reversed | Status |
| --- | --- | --- | --- | --- |
| 1 | Is a day's closing balance evaluated from what was known that day, or from the whole stream at the end? | Forward-only. Each day is assessed once, on that day, using every entry value-dated on or before it that has arrived. | The value-date-only reading leaves no day negative, so there is no overdraft fee at all and the rule becomes dead code. | open |
| 2 | Does a backdated entry cause already-closed days to be reassessed for a fee? | No. `reopenClosedDays = false`. | Day 2 and Day 4 would also take fees, giving three instead of one, and ACC-001 would close near 390 rather than 440.83. `Main` prints both readings. | chosen |
| 3 | Is the overdraft fee given back when the debit that caused it is reversed? | No. `refundFeeOnReversal = false`. The fee was correct on what was known at Day 5 close, and the ledger is append-only so it could only be offset, never removed. | ACC-001 would close at 465.84 instead of 440.83. | open |
| 4 | Are earlier days' accruals recomputed when a backdated entry changes those days? | No. An accrual freezes when its day closes. | Recomputing against final balances changes the capitalised total. | chosen |
| 5 | What happens if a correction arrives after interest has capitalised? | Out of scope. The last day is a hard close. This is the deliberately failing test. | Needs a back-valued interest adjustment and a cutoff date, which is a policy decision rather than a code change. | chosen |
| 6 | A settlement naming an authorization that never existed (E6, Auth-Z). | Rejected. No posting, funds do not move, `ORPHAN_SETTLEMENT` logged. | Force-posting it would move every balance from Day 4 onward by 180.00. | ruled |
| 7 | BHD 10.000 split three ways gives 3.333 each, totalling 9.999. Where does the 0.001 go? | Discarded. The account receives 9.999. | Giving it to the first or last instalment would make the ledger total exactly 10.000. | ruled |
| 8 | Is Auth-B approved or declined? | Declined. When it arrives, E7 has already landed and the ledger is −155.00, which is below zero before the 90.00 hold is even applied. | If approved, it holds 90.00 against available balance for the rest of the window. Criterion 5 is phrased "if Auth-B is approved", which suggests the author knew this was in question. | open |
| 9 | Does the order of events inside one day matter? | Yes, listed order. E7 is evaluated before E8 on Day 5. | If a day's events were tested against the end-of-day position, Auth-B would be approved. | chosen |
| 10 | The event list is not in day order — E10 is a Day 5 event listed after E9, a Day 6 event. | Stable sort by booking day, preserving listed order within each day. | Processing strictly as listed would close ACC-002's Day 5 before its credit arrived. | chosen |
| 11 | Do the first and last days of the window accrue interest? | Both do. Accrual runs on every day, and on the last day accrual happens before capitalisation. | Excluding either changes the capitalised total. | chosen |
| 12 | Is a balance of exactly zero negative? | No, strictly below zero. ACC-002 sits at zero for four days and is never charged. | ACC-002 would take four overdraft fees — in a currency that has no fee defined. | chosen |

## Rounding and currency

| # | Ambiguity | Resolution | If reversed | Status |
| --- | --- | --- | --- | --- |
| 13 | Rounding direction. | Half away from zero. One function, `Money.roundHalfUp`, two callers. | Half-to-even changes any amount landing exactly on a half — for example a 12.50 balance accruing 0.005. | chosen |
| 14 | The fee is stated in AED but ACC-002 is BHD, and no exchange rate is given. | BHD has no fee entry. A BHD account closing negative logs `FEE_CURRENCY_UNDEFINED` and is charged nothing. | Charging BHD 25.000 would be roughly ten times the intended penalty. This stream never reaches the path, so the exercise cannot catch a wrong answer here. | chosen |
| 15 | Is interest simple or compounding? | Simple. Accruals stay outside the balance until they capitalise, so they never earn interest themselves. | — | chosen |
| 16 | Interest basis. | Closing balance, not average or minimum daily balance. | — | chosen |

## Authorizations and settlements

| # | Ambiguity | Resolution | If reversed | Status |
| --- | --- | --- | --- | --- |
| 17 | A settlement for less than the hold — Auth-A held 200.00 and settled for 185.00. | The whole hold is released. The unused 15.00 returns to available balance without ever posting. | Keeping the remainder reserved would lower available balance from Day 4 onward. No authorization follows, so no figure changes here. | chosen |
| 18 | A settlement for more than the hold. | Post in full and log `OVER_SETTLEMENT`. Declining would hide a real unauthorised overdraft. | Not exercised by this stream. | chosen |
| 19 | Can one authorization be settled more than once? | No. The first settlement closes it and releases the hold. | Partial settlement would need the hold decremented and the authorization left open. | chosen |
| 20 | Does a hold expire if never settled? | No expiry. There is no window given, so a live hold stays live. | Auth-B is declined anyway, so nothing is exercised. | chosen |
| 21 | Is a declined authorization recorded? | Yes — a `DECLINED` transition plus an `AUTHORIZATION_DECLINED` error, so it appears in the day's output with the balance that caused it. | — | chosen |
| 22 | Does a hold reduce the ledger balance or only available balance? | Only available balance. The rule defines available as ledger balance minus holds, so a hold inside the ledger would be counted twice. | Holds would trigger overdraft fees, which the rule does not ask for. | chosen |

## Reversals

| # | Ambiguity | Resolution | If reversed | Status |
| --- | --- | --- | --- | --- |
| 23 | Must a reversal match its target's amount, value date and account? | Yes. A reversal mirrors every posting of the target with the same value date, and a reversal whose own account or value date disagrees is logged rather than applied. | — | chosen |
| 24 | Is a reversal idempotent? | Yes. Before mirroring, the engine checks whether the target has already been reversed and logs `DUPLICATE_REVERSAL` instead of posting twice. This works because reversals here are always full — `Reversal` carries no amount. | Partial reversals would make "already reversed" an incomplete answer and the guard would need to compare amounts. | chosen |
| 25 | Can a reversal itself be reversed? See the open question below. | Not decided. The current guard checks only whether the *target* has been reversed, so an event reversing E9 would pass and would put the original 620.00 debit back. | Allowing it means a chain of reversals is possible; blocking it means a mistaken reversal cannot be undone and needs a fresh debit instead. | open |
| 26 | Is there a limit on how far back an entry may be dated? | No limit, and no logging of the distance. E7 reaches back three days and E9 four. | A real system needs a back-value window tied to period close, which is the single control I would add before go-live. | chosen |

### Open question put to the interviewer (#25)

> If an event arrived saying "reverse E9", the duplicate guard would pass, because E9 itself has
> not been reversed — and mirroring E9's postings would put the original 620.00 debit back.
> That might be exactly right, undoing a mistaken reversal, or it might be something to block.
> Which do you want?

**My recommendation: block it.**

A reversal is a correction, not a general-purpose transaction. Once chains are allowed, working
out the net position means walking the whole chain, and the audit trail stops being readable at
a glance.

If a reversal was itself wrong, the clean fix is a fresh debit carrying its own reason. That
entry explains itself; an undo of an undo does not.

And blocking is the reversible choice. Allowing chains later is a one-line change, whereas
unwinding a chain already applied in production is not.

Implementation if blocked: when the target's postings are themselves of type `REVERSAL`, log a
new code `REVERSAL_OF_REVERSAL` and post nothing.

**Awaiting the ruling.** Until then the engine posts nothing and logs, which is the safe
default — a blocked reversal can be applied later, whereas a wrongly applied one has already
moved money.

## Bookkeeping and presentation

| # | Ambiguity | Resolution | If reversed | Status |
| --- | --- | --- | --- | --- |
| 27 | Is the opening balance a starting number or a posting? | A posting, type `OPENING_BALANCE`, on the first day of the window. Both accounts open at zero so no figure changes, but the balance function then has one code path instead of a special case. | — | chosen |
| 28 | Do all three instalments share one value date? | Yes, all three are value-dated Day 5 as the event states. | — | chosen |
| 29 | Is a zero accrual stored, or skipped? | Stored. One row per account per day, so the capitalised credit reconciles row by row. | Skipping would leave gaps in the accrual log and make the summation harder to audit. | chosen |
| 30 | Must replaying the same stream twice give the same result? | Yes. Nothing reads a clock or a random source, and this is asserted by its own test. | — | chosen |
| 31 | Should available balance and holds be printed, given the brief does not ask for them? | Yes. They are the only place a hold is visible, and without them a reader cannot see why an authorization was declined. | — | chosen |
