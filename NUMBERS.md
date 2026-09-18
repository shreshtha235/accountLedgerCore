# Numbers

Every constant in the codebase, and why it is that value.

The deliverable asks why each value and not half it. That question splits in two, and the split
matters: some of these are not mine to halve, and pretending otherwise would be dishonest. Those
are listed first, briefly. The ones I actually chose are defended at length, because those are
the only ones where halving is a question I get a say in.

## Fixed by the brief — halving these would disobey the specification

| Value | Where it lives | Note |
| --- | --- | --- |
| AED 25.00 overdraft fee | `LedgerPolicy.defaults()` | Stated under the non-negotiable rules. Held as a value in policy, not a constant in the engine, so a caller can pass a different fee without touching any other code — 25.00 is only the default. |
| 0.04% per day | `LedgerPolicy.defaults()`, as `4 / 10_000` | Stated. Also a policy value, not an engine constant. |
| AED 2 decimals, BHD 3 | `CurrencyCode` | Stated, and independently true — these are the ISO 4217 minor-unit scales. Halving BHD to one place would misstate every dinar amount by up to 0.0005 and would make the instalment split a different problem. |
| 3 instalments for E10 | `EventStream` | Stated by the event. |
| Six-day window, Day 1 to Day 6 | `EventStream.FIRST_DAY`, `LAST_DAY` | Stated. Passed into `replay` as arguments, so the engine itself has no six in it. |
| One fee per account per day | `LedgerEngine.assessFee` | Stated. Enforced as a query over existing postings rather than a counter, which is what makes it safe to assess a day more than once. |
| Opening balances 0.00 and 0.000 | `EventStream.accounts()` | Stated. |

## Chosen by me — these are the ones worth arguing about

### Money as a `long` of minor units

AED 1,200.00 is stored as `120000` fils; BHD 10.000 as `10000`. No decimal point exists anywhere
in memory.

*Why not `double`.* 0.1 has no exact binary representation. Over six days the error is invisible;
over six years of daily accrual it is not, and it would appear as a balance that cannot be
reconciled with its own postings.

*Why not `BigDecimal`.* It would work, and it is the more common choice. I rejected it because
correctness with `BigDecimal` depends on remembering to set a scale and rounding mode at every
operation, and the failure mode of forgetting is a silently wrong number. With whole minor units
the wrong thing is unrepresentable rather than merely discouraged — there is no fractional value
to misround, and division exists in exactly two places.

*Why not halve the precision.* Storing AED in whole dirhams rather than fils would lose every fee
and every accrual in this exercise; the largest accrual here is 0.26.

### The rate as a numerator over a denominator

`4 / 10_000`, held as two `long` fields, not as `0.0004`.

A decimal rate would put a rounding decision inside the rate itself before any balance touched
it. As a ratio, `balance.applyRate(4, 10_000)` multiplies an integer by an integer and rounds
once, at the end, in the one place rounding is allowed to happen.

*Why not halve it.* 0.02% is a different product. This value came from the brief.

### Half away from zero

`Money.roundHalfUp` is the only rounding in the program, with exactly two callers — the daily
accrual and the instalment split.

*Why not half to even.* Half-to-even is the more common banking convention and is defensible. I
chose half away from zero because it matches what a customer expects and is easier to explain
when someone queries a fil. It is a real choice, not an obvious one, and it is recorded as
ambiguity 13.

It changes an answer only when a value lands exactly on a half. A balance of AED 12.50 accrues
exactly 0.005: half away from zero pays 0.01, half to even pays 0.00. Nothing in this stream
lands on a half, so this exercise would not catch the wrong choice — which is precisely why it is
written down.

### Fee assessed before interest at day close

The day closes in a fixed order: apply the day's events, assess the fee, then recompute the
balance and accrue on the result.

*Why this order.* The fee is a posting value-dated to the same day, so it sits inside that day's
own closing balance and can take a day from positive to negative — and a negative day earns
nothing.

*What the other order would change.* On this stream, nothing. Day 5 closes at −155.00 before the
fee, which is already negative, so the accrual is zero either way. But take a day closing at
+20.00 that then attracts a 25.00 fee: fee-first accrues nothing, interest-first accrues 0.01 on
a day the customer ended overdrawn. The order matters; it just is not exercised here.

### Accruals stored per day, not as a running total

One `Accrual` row per account per day, including explicit zeros — 12 rows across this window.

*Why not a running total.* Three reasons. The capitalised credit can be reconciled row by row
against the days that produced it. A later correction can recompute a single day and post the
difference. And the deliberately failing test can *measure* the gap between what was paid and
what was earned; had only the total been kept, that gap could not even be calculated.

*Why store zeros.* So the accrual log has no gaps and the row count is predictable, which is
itself asserted as an invariant.

### The capitalised credit is defined as the sum of the stored accruals

Not the rate applied to an aggregate, and not the sum then rounded.

This is what makes the brief's rule — the rounded daily accruals must sum exactly to the
capitalised total — hold by construction rather than by reconciliation. There is no remainder,
so there is nothing to discard, which is the reason acceptance criterion 8 is refused.

On this stream the two approaches differ: the six rounded accruals sum to 0.83, while applying
the rate once to the total of the accrual bases and rounding gives 0.82. A fil, and the account's
interest would no longer tie to its own rows.

### Debits and fees stored as negative amounts

So a balance is a plain sum with no sign convention to remember at each call site. `Posting`
enforces it — a fee or settlement must not be positive, a credit or interest credit must not be
negative — so a flipped sign fails at construction rather than quietly changing the answer.

### The opening balance as a posting, not a starting number

Type `OPENING_BALANCE`, value-dated the first day of the window. Both accounts open at zero so no
figure changes, but the balance function then has one code path instead of a special case, and it
is how a real ledger records an opening position.

### `Integer.MAX_VALUE` as the "no booking-day bound" sentinel

`balance(account, valueDay)` delegates to the bitemporal form with this as the bound.

*Why not `Optional<Integer>`.* A day number cannot plausibly approach it, the two-argument
overload means no caller ever writes the sentinel, and it keeps the most-called query on the hot
path free of allocation. If days ever became real dates this would have to change.

### The fee held as a map from currency, with no BHD entry

The brief states the fee in AED, but ACC-002 is a BHD account and no exchange rate appears
anywhere in the problem. So `feeFor(BHD)` returns empty and a BHD account closing negative
records `FEE_CURRENCY_UNDEFINED` and is charged nothing.

*Why not charge BHD 25.000.* A dinar is worth roughly ten dirhams, so that is about ten times the
intended penalty. *Why not convert.* There is no rate to convert with. Failing loudly is the only
honest option, and this stream never reaches that path, so the exercise could not have caught a
wrong answer here.

### Defaults on the four disputed rulings

`reopenClosedDays = false`, `refundFeeOnReversal = false`, `instalmentResidual = DISCARD`.

The first two are documented as open in `AMBIGUITIES.md`; `Main` prints the first both ways
rather than asserting which is right. The third was ruled by the interviewer. All three are
policy fields precisely because they are arguable.

## Build choices

| Choice | Reason |
| --- | --- |
| `--release 21` | Records, sealed interfaces and exhaustive pattern switches are load-bearing. 21 is the current LTS, so the grader does not need JDK 25 even though that is what this was built on. |
| No runtime dependencies | Nothing here needs a library, and a money core with a dependency tree is a liability. |
| JUnit 5, test scope only | — |
| The failing test behind a Maven profile | `mvn test` stays green; `mvn test -Pfailing` runs the one that is meant to fail. Surefire's `excludedGroups` overrides `-Dgroups`, so a profile is the only reliable way to do this. |
