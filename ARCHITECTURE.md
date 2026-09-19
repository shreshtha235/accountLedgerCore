# Architecture, trade-offs, and production considerations

Written against the implementation in this repository, not against a generic ledger. Class and
method names refer to real code.

## 1. Append-only at scale

### What breaks first at 100×

Not storage. The read path, and specifically the authorization decision.

`Ledger.balance` is a linear scan over every posting ever written. `Ledger.activeHolds` is
worse: it calls `latestTransitions`, which allocates a fresh `LinkedHashMap` and folds the entire
transition log on **every** call, and four separate query methods call it. So
`LedgerEngine.applyAuthorize` costs O(postings + transitions) and allocates a map per decision.

That is the method with a latency budget. A card authorization has to answer in a few hundred
milliseconds; day close and fee assessment are batch work and can be slow without anyone
noticing. So the first symptom at scale is not a wrong number, it is authorization timeouts —
which surface as declines to customers rather than as an alert, and are correspondingly harder to
attribute.

The second thing to break is day close, which is quietly quadratic. `LedgerEngine.closeDay`
calls `balance` twice per account per day, and with `reopenClosedDays` enabled it re-assesses
every day from the start of the window, so the work grows with the square of the window length.
At a six-day window that is invisible; at a year it is not.

### Where unbounded state accumulates

Four places, in order of how fast they grow:

- **The accrual log.** One `Accrual` row per account per day, forever, including explicit zeros.
  Accounts × days. This is the fastest-growing structure in the system and the first that would
  need partitioning.
- **The posting log.** By design, correctly, and it is the source of truth.
- **The transition log.** Never aged out, so settled and declined authorizations accumulate
  alongside live ones — and because `latestTransitions` folds the whole log, dead rows slow down
  every live decision.
- **`Ledger` itself,** which holds all four lists in memory with no eviction. Memory has no
  ceiling.

The real problem is not the storage cost. It is that read cost grows with total history and never
comes down, because nothing is ever removed and nothing is cached.

### The cheapest structural change

A per-account, per-day closing-balance snapshot, plus memoising `latestTransitions`.

A balance query then reads the newest snapshot at or before the cutoff and applies only the
postings after it. Read cost becomes bounded by the snapshot interval instead of by all history.

The load-bearing constraint is that a snapshot must be a **cache, never a record**. A backdated
entry invalidates every snapshot for that account whose value day is at or after the arriving
entry's value day. Because the value day is known at arrival, the invalidation set is computable
exactly and cheaply. Keeping snapshots outside the four append-only lists preserves the
append-only guarantee, since a discardable cache is not part of the ledger.

It is the cheapest option because it changes no data model, touches no event, needs no migration,
and can be retrofitted behind the existing `Ledger.balance` signature — callers never learn it
happened.

It defers rather than solves. Log growth is untouched, so partitioning by account and date range
is still coming. And it does nothing about write amplification: a bulk back-value correction file,
which real banks receive, invalidates a wide range at once. Bounding how far back an entry may
reach is the complementary fix, and it belongs to the next section.

## 2. Value-dated entries in production

Every `Posting` here carries a `bookingDay` and a `valueDay`, which means two truths coexist: what
the books said at the time, and what they say now. Almost every operational failure downstream is
someone answering with the wrong one. `ReportPrinter` prints both for exactly that reason, and
this ledger's own Day 5 is the illustration — it closed at −180.00 and now reads 440.00.

### Operational surface

Statements already issued become wrong when a backdated entry lands, so there must be a standing
policy on reissue versus a correction on the next cycle. Front-line staff need the as-at view,
which `Ledger.balance(account, valueDay, knownByBookingDay)` provides, or they will quote a
customer a balance that never appeared on any statement.

Day-end reconciliation against correspondent accounts and against the general ledger is disturbed,
because a figure in an already-signed-off period moves. Interest and fee recalculation stops being
an exception and becomes a scheduled process. Disputes get harder: a customer charged a fee whose
cause was later reversed — precisely this ledger's Day 5 fee against E9 — will complain, and the
system, the statement and the agent all have to give the same answer.

Payment cut-off times and value-date conventions have to match the ledger's understanding exactly,
including what happens when a value day falls on a non-working day. This implementation has no
business-day calendar at all, so it accrues interest and assesses fees on every day of the window.
The UAE working week moved to Monday–Friday in 2022, and practice still varies between
institutions, so that is something to pin down rather than assume.

### Regulatory surface

The CBUAE is the licensing and supervisory authority, and prudential returns are as-at a date. A
backdated entry restates a figure that has already been filed, which turns a data correction into a
reporting event. Under IFRS the same entry can move revenue or expense recognition across a closed
period.

Fees in the UAE carry VAT at five percent, and a fee's tax point is a date, so backdating a fee can
reach into a filed VAT period. Consumer protection rules require each charge to be disclosed and
justifiable, which in practice means being able to reproduce the exact basis on which a fee was
assessed — the as-at balance, not the current one. AML monitoring keys off what was seen and when,
so it must run on booking day; keying it to value day would let a backdated entry rewrite a pattern
monitoring had already cleared. Retention obligations require every version of the truth to be
reproducible, commonly five years, though the precise period should be confirmed with compliance
rather than assumed.

And if the entity or a window within it is Sharia-compliant, daily interest accrual is not an
applicable construct at all; the whole of `LedgerEngine`'s accrual and capitalisation path would
need replacing with a profit-sharing mechanism.

### The one control before go-live

**A hard back-value window enforced at the point of entry, aligned to the accounting period close,
with named human approval and a full audit record for anything beyond it.**

This implementation has no such limit — `AMBIGUITIES.md` entry 26 records that as a deliberate gap.
E7 reaches back three days and E9 four, and nothing would stop an entry reaching back three years.

It is the right single control because it converts an unbounded liability into a bounded one.
Ordinary corrections land inside an open period where recomputation is safe and cheap; anything
that would restate a reported period is stopped and escalated rather than applied silently. It also
bounds the snapshot-invalidation cost from section 1, so one control protects both correctness and
performance.

If a second were allowed, it would be a daily reconciliation that rebuilds every balance from the
posting log and compares it against the snapshot — a cache that is never verified is a liability.

## 3. Authorization lifecycle

`AuthorizationTransition.State` has four values: `APPROVED`, `DECLINED`, `SETTLED`, `RELEASED`.
One observation about the code first, because it is a genuine finding: **`RELEASED` is declared and
never written anywhere.** `LedgerEngine.applySettle` records `SETTLED` and releases the whole hold
in that transition, so there is no path that produces a `RELEASED` row. It is dead today, and it is
the state every unimplemented ending below would need.

A unifying rule worth stating: of every ending in this section, only settlement, over-settlement
and chargeback create postings. Every other ending moves available balance alone. That single fact
removes most of the confusion in this area.

### Endings this implementation handles

**Declined at request.** Not enough headroom when asked. Auth-B in this stream: available was
−155.00 before the 90.00 hold was even applied. Behaviour: no hold, no posting, a `DECLINED`
transition, and an `AUTHORIZATION_DECLINED` error carrying the balance that caused it — because
declines generate complaints and must be explainable months later.

**Settled for less than the hold.** A restaurant authorising an estimate including a tip and
settling for less; a fuel pre-auth settling for the litres actually dispensed. Auth-A here: held
200.00, settled 185.00. Behaviour: post the settled amount, release the hold in full. The unused
15.00 returns to available balance without ever posting — it was a reservation, never money.

**Settled for more than the hold.** A tip added after authorization, or hotel incidentals.
Behaviour: post in full, release the hold, raise `OVER_SETTLEMENT` for review. Never decline a
settlement for insufficient funds — that produces an unauthorised overdraft, which is a legitimate
outcome that must be visible rather than suppressed.

**A second settlement against a closed authorization.** Behaviour: rejected with
`AUTHORIZATION_NOT_OPEN`, no posting. The first settlement closes the authorization.

**No authorization at all.** Auth-Z here. Behaviour: `ORPHAN_SETTLEMENT`, no posting, no
transition — there is nothing to transition, which is the distinction acceptance criterion 4 gets
wrong.

### Endings this implementation does not handle

Each would write a `RELEASED` row, which is why that state exists.

**Expiry.** The merchant never completes — a cancelled hotel stay, an abandoned online order.
Mandate: auto-release after a window configured per merchant category, not one global number, since
fuel and hospitality behave nothing like retail. Record the release as its own transition. Auth-B
in this stream is never settled, and with no expiry a hold would sit against the account
indefinitely.

**Void or reversal by the merchant or acquirer.** The transaction is cancelled before settling, or
a terminal times out. Mandate: release in full immediately, and make it idempotent, because these
messages are retransmitted as a matter of routine. This needs a sixth `LedgerEvent` type; the
current five cannot express it.

**Multiple partial settlements against one authorization.** A split shipment from one order.
Mandate: decrement the hold on each and keep the authorization open until the hold is exhausted, it
expires, or it is explicitly closed. The current model cannot represent a partially consumed hold —
`Ledger.holdOf` returns the full `APPROVED` amount or nothing.

**Chargeback after settlement.** Mandate: the authorization is already closed and stays closed; the
dispute is a new posting, never a reopening. Worth stating explicitly because this is the case where
people reach for mutation, which the append-only rule forbids.

**Account-level termination.** The account is blocked, frozen, closed, or hits a sanctions match
while a hold is live; or the holder dies. Mandate: holds must not silently vanish — freeze them and
require manual disposition, because an automatic release on a blocked account is a route for funds
to leave.

**Fraud cancellation after approval.** Mandate: release the hold, but record the reason distinctly
from an expiry. The two mean entirely different things to fraud, to disputes and to the customer,
and a single `RELEASED` state with no reason code cannot tell them apart — which is an argument for
the `reason` field `AuthorizationTransition` already carries.

**Duplicate authorization.** The same request arrives twice on a retry. Mandate: idempotency on the
authorization reference, so the second creates no second hold. This is the highest-value guard in
the area and its absence is the most common production defect. The current code has a real bug
here: a second `Authorize` for an existing reference appends another transition that silently
supersedes the first.

**Stale hold sweep.** A release message is lost and a hold sits indefinitely, understating available
balance. Mandate: a scheduled sweep reporting holds past their expected window, reconciled against
the scheme's own records. Expiry logic nothing ever runs is indistinguishable from no expiry logic.

## 4. What was cut, and the risk each defers

| Cut | Production risk deferred |
| --- | --- |
| Double-entry contra postings | Only the customer side exists. No contra account for fee income or interest expense, so the ledger does not balance in the accounting sense and could not be handed to finance. The largest single omission. |
| Persistence | No durability, no crash recovery, and startup replay time grows with total history. |
| Concurrency control | `LedgerEngine` is single-threaded. In production two authorizations on one account could both pass `available()` and over-commit the balance. Needs per-account serialisation or a version check on the balance read. |
| Inbound idempotency keys | A retransmitted event posts twice. Payment rails retransmit routinely, so this is the most likely first incident. |
| Authorization reference idempotency | A repeated `Authorize` for the same reference silently supersedes the first transition instead of being rejected. A live bug, not just an omission. |
| Period close and a back-value limit | Any entry can restate a reported period. The control named in section 2. |
| Post-capitalisation interest correction | Demonstrated by `BackValuedInterestTest`, which fails by AED 0.48 rather than hiding the gap. Needs a back-valued adjustment and a cutoff date, both policy decisions. |
| Business-day calendar | Interest accrues and fees are assessed on days the bank is shut. |
| Currency conversion | A fee cannot be charged to an account in another currency. `feeFor(BHD)` returns empty and logs `FEE_CURRENCY_UNDEFINED` rather than guessing a rate — a loud gap by choice, and one this event stream never reaches. |
| Hold expiry, and the `EXPIRED` / `PARTIALLY_SETTLED` states | A lost release pins a hold forever and understates available balance indefinitely. |
| An acquirer void event | The five `LedgerEvent` types cannot express a merchant cancelling before settlement. |
| Balance snapshots and memoised holds | Every query is a linear scan and `latestTransitions` reallocates per call. The first thing to break at scale, on the one path with a latency budget. |
| Access control and maker-checker | A single operator could post an unreviewed correction. |
| Metrics and observability | No counters on fee assessments, accrual totals or hold ages, so drift would go unnoticed until a reconciliation failed. |
| Operational workflow on errors | Failures are recorded in `ErrorLog` but nothing acts on them. In production a rejected settlement is the start of a process involving a person, not the end of one. |
| Rate sophistication | One flat simple rate. No tiering, no scheduled rate changes, no mid-period change — which is itself a value-dated problem and would reuse the same machinery. |
