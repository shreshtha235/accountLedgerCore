# Ambiguities

| Ambiguity | Resolution |
| --- | --- |
| Does a backdated entry cause already-closed days to be reassessed for a fee? | No. Fee is assessed once, on the day it closes. We have kept a boolean variable in code for this ambiguity to resolve as per business decisions`reopenClosedDays = false` |
| Is the overdraft fee given back when the debit that caused it is reversed? | No. Fee was correct on what was known at given day close, and the ledger is append-only so it could only be offset, never removed. We have kept a boolean variable `refundFeeOnReversal = false` for this decision|
| Are earlier days' accruals recomputed when a backdated entry changes those days? | No. An accrual freezes when its day closes. |
| What happens if a correction arrives after interest has capitalised? | Out of scope. The last day is a hard close. This is the deliberately failing test.  We should keep a fixed window of the allowed backdates example last 30 days and then should do the capitalizations on the last day of that.|
| A settlement naming an authorization that never existed (E6, Auth-Z). Should be posted/flagger or just rejected. | Rejected. No posting, funds do not move, `ORPHAN_SETTLEMENT` logged. |
| BHD 10.000 split three ways gives 3.333 each, totalling 9.999. Where does the 0.001 go? what if this accumulation becomes some big number after some days. | Discarded. The account receives 9.999. We should keep a threshold on the allowed discarded amount.|
| Does the order of events inside one day matter? | Yes, listed order. E7 is evaluated before E8 on Day 5. |
| The event list is not in day order — E10 is a Day 5 event listed after E9, a Day 6 event. | Stable sort by booking day, preserving listed order within each day. |
| Do the first and last days of the window accrue interest? | Both do. Accrual runs on every day, and on the last day accrual happens before capitalisation. |
| Is a balance of exactly zero negative? | No, strictly below zero. ACC-002 sits at zero for four days and is never charged. |
| Is interest simple or compounding? | Simple. Accruals stay outside the balance until they capitalise, so they never earn interest themselves. |
| Interest basis | Closing balance, not average or minimum daily balance. |
| Should `valueDay` allow future dates? | Not decided. Current code accepts it. A future valueDay would accrue interest on a day that has not closed. Likely a data error in most cases but valid for forward-dated instruments. Awaiting product ruling. |
| Should partial settlement keep the auth open for the remaining amount? | Not decided. First settlement closes the auth entirely and releases the full hold. Split-shipment and hotel-checkout scenarios need the hold to decrement and stay open. Awaiting product ruling. |
| Is the fee model correct for joint accounts? | Not decided. Engine ties one fee to one `accountId`. A joint account may have two cardholders — fee eligibility and amount depend on the account agreement. No ruling exists. |

## Authorization and Settlement

| Ambiguity | Resolution |
| --- | --- |
| The fee is stated in AED but ACC-002 is BHD, and no exchange rate is given. | BHD has no fee entry. A BHD account closing negative logs `FEE_CURRENCY_UNDEFINED` and is charged nothing. |
| A settlement for more than the hold. | Post in full and log `OVER_SETTLEMENT`. Declining would hide a real unauthorised overdraft. |
| Can one authorization be settled more than once? | No. The first settlement closes it and releases the hold. |
| Does a hold expire if never settled? | No expiry. There is no window given, so a live hold stays live. |
| Is a declined authorization recorded? | Yes — a `DECLINED` transition plus an `AUTHORIZATION_DECLINED` error, so it appears in the day's output with the balance that caused it. |
| Does a hold reduce the ledger balance or only available balance? | Only available balance. The rule defines available as ledger balance minus holds, so a hold inside the ledger would be counted twice. |


## Reversals

| Ambiguity | Resolution |
| --- | --- |
| Must a reversal match its target's amount, value date and account? | Yes. A reversal mirrors every posting of the target with the same value date. |
| Is a reversal idempotent? | Yes. Before mirroring, the engine checks whether the target has already been reversed and logs `DUPLICATE_REVERSAL` instead of posting twice. |
| Can a reversal itself be reversed? | Not decided. Current guard checks only whether the target has been reversed, so an event reversing E9 would pass. Recommendation: block it. Awaiting ruling. |
| Is there a limit on how far back an entry may be dated? | No limit. E7 reaches back three days and E9 four. A real system needs a back-value window tied to period close. |

## Bookkeeping and presentation

| Ambiguity | Resolution |
| --- | --- |
| Do all three instalments share one value date? | Yes, all three are value-dated Day 5 as the event states. |
| Is a zero accrual stored, or skipped? | Stored. One row per account per day, so the capitalised credit reconciles row by row. |
| Should available balance and holds be printed, given the brief does not ask for them? | Yes. They are the only place a hold is visible, and without them a reader cannot see why an authorization was declined. |
