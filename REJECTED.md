# Rejected

Two parts: the acceptance criteria refused and why, then the approaches abandoned while
building.

Every refusal below is asserted in `AcceptanceCriteriaTest`, so the reasoning is executable
rather than only written down. The arithmetic is given in full so each argument stands without
needing this document's author present.

---

# Part 1 — Acceptance criteria

Of the eight stated criteria, four are wrong, one is right in intent but defective as worded,
and three are correct.

## 1. ACCEPTED — "The Day 2 closing ledger balance, evaluated at end of Day 5 and before any fee is assessed, is AED −370.00."

Correct. At the end of Day 5 the entries value-dated on or before Day 2 are E1's credit of
1,200.00, E2's debit of 950.00, and E7's debit of 620.00, which had just arrived backdated.

    1,200.00 − 950.00 − 620.00 = −370.00

Auth-A's 200.00 hold is not a ledger entry and does not appear. E9 has not arrived yet.

Worth noting that this is the only one of the eight that is true under *either* reading of the
overdraft rule, which makes it the only reading-independent criterion in the set.

## 2. REFUSED — "E7 causes exactly one overdraft fee to be assessed, on Day 2."

The count is right. The day is wrong. And more damagingly, the two halves cannot both be true
under any reading of the rule.

The rule says a fee is "assessed once per day per account" and "booked with value_date equal to
the day assessed". E7 arrives on Day 5, so Day 5 is the day assessed, and the fee is value-dated
Day 5. Day 2 had already been assessed on Day 2, when it closed at 250.00 and was positive.

Now take the two possible readings:

- **Days are never reopened.** Exactly one fee, and it sits on Day 5. The count matches, the day
  does not.
- **A backdated entry reopens earlier days.** Then Day 2 does take a fee — but walking forward
  from Day 2 with that fee in the balance gives Day 3 at 5.00, Day 4 at −180.00 and Day 5 at
  −205.00, so Days 4 and 5 take fees too. Three fees, not one. The day matches, the count does
  not.

There is no interpretation under which "exactly one" and "on Day 2" are simultaneously true. The
test asserts one fee value-dated Day 5 under the default policy, and three fees under the
reopen-history policy, which demonstrates both halves of this argument from the running code.

## 3. ACCEPTED — "The Day 4 settlement of Auth-A must be accepted."

Correct. Auth-A was approved on Day 2 with a 200.00 hold, and E5 settles it for 185.00, which is
within what was reserved. Honouring a commitment already made is not re-tested against the
balance. The settlement posts −185.00 value-dated Day 4 and releases the hold in full.

## 4. REFUSED AS WORDED, intent accepted — "Any settlement referencing an authorization ID not present in the ledger must be rejected and the funds must not leave the account."

The behaviour is right and is implemented: E6 references Auth-Z, which has no authorization, so
it is rejected, `ORPHAN_SETTLEMENT` is logged, and no posting is created. Day 4 and everything
after it are unaffected by the 180.00.

The wording is wrong in one specific and consequential way. It says "not present in **the
ledger**". An authorization is never a ledger entry. That is the whole content of criterion 5 —
a hold reduces available balance and not ledger balance — and it is why E3, which approved
Auth-A, produced no posting at all.

Read literally, then, *no* authorization ID is ever present in the ledger, so every settlement
would be rejected, including Auth-A's. That directly contradicts criterion 3.

The criterion needs to say the authorization register, not the ledger. The implementation looks
up the transition log, and the test asserts that E3 produced no postings, which is the fact that
makes the stated wording unimplementable.

## 5. ACCEPTED, but this stream cannot exercise it — "If Auth-B is approved, its hold reduces available balance but not ledger balance."

The statement is correct, and the conditional phrasing is telling — whoever wrote it appears to
have known Auth-B's approval was in question.

In this stream the condition is never met. When E8 arrives on Day 5, E7 has already been applied
earlier that same day, so the ledger stands at:

    1,200.00 − 950.00 − 620.00 + 400.00 − 185.00 = −155.00

Auth-A was released when it settled on Day 4, so there are no other holds. That is already below
zero before the 90.00 is applied, so Auth-B is **declined** and no hold is ever created.

The underlying rule is exercised elsewhere and the test asserts it there: on Day 3, Auth-A's
200.00 hold left the ledger balance at 650.00 and available at 450.00.

## 6. REFUSED — "After E9, all balances and fees return to their pre-E7 values."

Wrong twice over, and the second reason is structural.

**Balances.** Replaying the stream with E7 and E9 both removed closes ACC-001 at 466.03. With
them present it closes at 440.83. The two 620.00 entries cancel each other, but the 25.00 fee
does not, and neither does the interest the affected days failed to earn. There is a permanent
scar.

**Fees.** The word "return" describes something this system is forbidden to do. The brief's own
non-negotiable rule says no event record is ever mutated or deleted, so an assessed fee cannot be
un-assessed. The most that could happen is a compensating credit — a new entry, not a reversion
to a previous value. This criterion asks for behaviour the append-only rule prohibits.

The test asserts both figures, asserts they differ, and asserts that E7's posting, E9's mirror
and the fee are all still present — nothing was removed.

## 7. REFUSED — "The three BHD instalments in E10 must each be BHD 3.334."

    3.334 × 3 = 10.002

The event credits 10.000. Posting 10.002 credits the account with 0.002 more than it was ever
given, which is inventing money.

BHD has three decimal places, so 10.000 ÷ 3 = 3.333… cannot be represented exactly. The only
defensible splits are:

- 3.333 three times — totals 9.999, falls 0.001 short, and is the ruling in force
- 3.334 + 3.333 + 3.333 — totals exactly 10.000

Never 3.334 three times. The test asserts the posted instalments are 3.333 and asserts the
arithmetic that refutes the criterion directly.

## 8. REFUSED — "If the rounded daily interest accruals do not sum to the capitalized total, the remainder is discarded."

This contradicts the brief's own non-negotiable rule, which states that the rounded daily
accruals **must** sum exactly to the capitalised total.

That rule is an invariant, and it tells you how to compute the total: the capitalised figure is
*defined* as the sum of the stored rounded accruals. A mismatch therefore cannot arise, and there
is no remainder to discard. This criterion instead treats a hard invariant as a condition that
might fail, and then specifies throwing the difference away.

The two approaches genuinely differ here. The six rounded accruals are 0.10, 0.10, 0.26, 0.19,
0.00 and 0.18, summing to **0.83**. The accrual bases total 2,055.00, and applying the rate once
to that and rounding gives **0.82**. Under this criterion that fil would be silently lost, and the
account's interest would no longer reconcile against its own accrual rows.

Any mismatch here means a bug. Discarding it hides the bug and loses money.

---

# Part 2 — Approaches abandoned while building

## The reopen-history fee design

My first reading had a backdated entry reassessing every already-closed day. It produced three
fees — Days 2, 4 and 5 — and closed ACC-001 at 390.81.

I abandoned it because nothing in the brief asks for it. I had invented it. Rereading the rule,
"booked with value_date equal to the day assessed" is best understood as a safeguard against
exactly what I was doing: it pins the fee to the day being assessed rather than letting it drift
back onto Day 2.

It is not deleted, though. It survives as `reopenClosedDays`, defaulting to false, and `Main`
prints both readings side by side — because the brief does not actually settle the question and
printing both is more honest than picking one silently.

## Posting E6's 180.00

I had the orphan settlement force-posted to Day 4 before being told to reject it. That had Day 4
closing at 285.00 rather than 465.00 and moved every figure after it. Corrected.

## Twenty-eight types across six packages

The first version of the design had `AccountId`, `Rounding`, `CurrencyMismatchException`,
`Authorization`, `AccountSnapshot`, `DayReport`, `ReplayResult`, four separate store classes and
six separate engine classes, in `domain` / `store` / `engine` / `policy` / `fixture` / `report`.

It is now fifteen files in one package. The four stores became `Ledger`; the six engine classes
became `LedgerEngine` with private methods; `Rounding` folded into `Money`.

The worst part of it was `AccountSnapshot`, `DayReport` and `ReplayResult`, which were a second
copy of state the ledger already held. "Day 4's closing balance", "the fee on Day 5" and "Day 4's
errors" are all queries. Keeping a parallel representation could only ever drift from the source.
The printer and the tests now both query the ledger.

## Twelve configurable policy fields

The rounding mode, the accrual day range, accrue-before-capitalise, zero-is-not-negative,
intraday ordering, hold-release-on-settlement, the backdating limit and the opening-balance
treatment were all originally policy fields. I had conflated *documenting* a decision with
*making it configurable*. Nobody ever flipped any of them, which is the definition of config for
a value that never varies.

They are now named constants, still documented in `AMBIGUITIES.md`. Policy kept the four
genuinely disputed rulings, plus the rate and the fee table.

## `Comparable<Money>`

Added out of habit. Nothing ever compared two amounts — the overdraft check and the availability
test both subtract and then ask `isNegative()`. Removed.

## A running balance per account

Considered and rejected before any of it was written. A backdated entry invalidates any stored
balance, and there is no safe way to patch one. Balances are recomputed from the postings every
time. It is slower and it is correct, and the cost is marked in the code with the production fix
named — a per-day snapshot invalidated from the value date of each arriving entry.

## Floating point for the interest

Rejected before writing any of it. 0.1 is not representable in binary; six days hides it, six
years does not.

## Editing the fee out when E9 arrives

The obvious instinct, and forbidden outright by the append-only rule. Recorded because it is the
mistake the next person will reach for. If the fee is ever to be given back it must be a new
compensating credit, which is what `refundFeeOnReversal` does.

## `-Dgroups=failing`

The first attempt at making the failing test runnable on demand. It silently ran zero tests,
because Surefire's `excludedGroups` overrides `-Dgroups`. Replaced with a Maven profile,
`mvn test -Pfailing`.

## Thirteen wrong test expectations

My first pass at `LedgerEngineTest` asserted balances on the last day of each test's window,
having forgotten that the capitalised interest credit lands on exactly that day. Thirteen tests
failed with values like 465.45 against an expected 465.00 — the 0.45 being interest. The engine
was right and my expectations were wrong. Fixed by extending each test window by one day so the
asserted day is clean.

---

# Part 3 — Deliberately not built

Each of these is a real gap. None is an oversight.

| Not built | Risk it defers |
| --- | --- |
| Double-entry contra postings | Only the customer side is tracked, with no contra account for fee income or interest expense. The ledger does not balance in the accounting sense and could not be handed to finance. The largest single omission. |
| Persistence | No durability, no crash recovery, and replay time grows with history. |
| Concurrency control | Two authorizations on one account could both pass the availability test and over-commit the balance. |
| Inbound idempotency keys | A retransmitted event posts twice. Given how routinely payment rails retransmit, the most likely first production incident. |
| Period close and a back-value window | An entry can reach arbitrarily far back and restate an already-reported period. This is the single control I would add before go-live. |
| Post-capitalisation interest correction | Demonstrated by the failing test rather than hidden. Needs a back-valued adjustment and a cutoff date, both policy decisions. |
| Business-day calendar | Every day is treated as a working day, so interest accrues and fees are assessed on days the bank is shut. |
| Currency conversion | A fee cannot be charged to an account in another currency. Fails loudly rather than guessing a rate. |
| Hold expiry, and the `EXPIRED` and `PARTIALLY_SETTLED` states | A lost release message pins a hold forever and understates available balance indefinitely. |
| An acquirer's explicit authorization void event | Nothing in the stream reaches it. |
| Balance snapshots | Every balance query is a linear scan over all postings. Fine at ten events; the first thing to break at scale, and it breaks on the authorization path, which is the one with a latency budget. |
| Access control and maker-checker | A single operator could post an unreviewed correction. |
| Metrics and observability | No counters on fee assessments, accrual totals or hold ages, so silent drift would go unnoticed until a reconciliation failed. |
| Operational workflow on errors | Failures are recorded but nothing acts on them. In production a rejected settlement is the start of a process involving a person. |
