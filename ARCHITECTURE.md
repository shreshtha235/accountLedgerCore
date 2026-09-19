# Architecture, trade-offs, and production considerations

Written against the implementation in this repository, not against a generic ledger. Class and
method names refer to real code.

## 1. Append-only at scale

The restated balance — `balance(id, day)` — uses a per-account `TreeMap` snapshot. When called,
it finds the nearest cached day, scans only postings after that, and stores the result. When a
backdated posting arrives, entries from its value day onwards are cleared.

### What breaks first at 100×

**Point-in-time balance queries.** `balance(id, day, bookingDay)` has no snapshot and does a
full scan every time. The report printer calls this for every "at close" column, for every
account, for every day. At 100× accounts and 100× days over a 100× bigger posting list, this
collapses first.

**Authorization decision speed.** `activeHolds` and `stateOf` use `latestTransitionCache` —
a `LinkedHashMap` updated on every `record()` call, so the current state of each auth ref is
O(1) to look up. The `latestTransitions(asAtDay)` overload used for point-in-time auth queries
does a full scan. At scale the full-scan path times out — a timeout shows up as a decline to
the customer, not as an alert.

**Replay from Day 1 on every run.** `replay()` reprocesses the full event list from the
beginning every time. No checkpoint exists. At 100× events this means replaying months of
history just to process today's work.

**Single-threaded processing.** The engine handles one account at a time. Accounts share no
state so there is no reason they cannot run in parallel.

### Where state accumulates unbounded

- **Posting log** — grows forever in RAM with no eviction or archival.
- **Transition log** — settled and declined authorizations never cleaned up. The cache covers
  the hot path, but the point-in-time overload (`latestTransitions(asAtDay)`) folds the whole
  log on every call, and the log grows without bound.
- **Accrual log** — one row per account per day including explicit zeros. Grows fastest.
- **Snapshot map** — every unique day queried adds an entry and nothing evicts old ones.

### Cheapest structural change that defers the problem

Apply the same snapshot pattern to the point-in-time query — cache by `(valueDay, bookingDay)`
pair instead of just `valueDay`. Same invalidation logic. Only `Ledger.java` changes. This cuts
the scan cost for the "at close" column the same way the existing snapshot cut the restated
balance cost.

### What that still does not fix

- **Unbounded lists in RAM** — move postings, transitions, and accruals to a database. Queries
  become indexed lookups, restarts survive, storage scales independently of the JVM heap.
- **Replay from Day 1** — store a daily checkpoint and resume from there instead of replaying
  the full history every run.
- **Single-threaded** — fan out replay per account across threads or nodes.
- **Concurrent writes at scale** — at 100× events arrive from multiple sources at the same time
  — mobile app, ATM, payment network, back-office. Without ordering, two events on the same
  account both pass the balance check. A message queue like Kafka gives ordering per account so
  the engine always processes events in sequence. The append-only model makes this a natural fit
  — Kafka is itself append-only. Without Kafka the alternative is per-account locks or optimistic
  locking in the database.

## 2. Value-dated entries in production

Every posting carries a `bookingDay` (when the system learned about it) and a `valueDay` (when it
counts economically). When these differ, two versions of the truth coexist. Day 5 in this ledger
shows this — it closed at AED −180.00 and now reads AED 440.00 after a backdated entry arrived.

### Operational surface

When a backdated entry lands, balances change for days that have already passed.

- **Customer statements** already sent for those days are now wrong. The bank needs a policy on
  whether to reissue or correct on the next cycle.
- **Front-line staff** quoting a balance must use the as-at view — `balance(id, day, bookingDay)` —
  or they will give the customer a number that never appeared on any statement.
- **Fees and interest** for the affected days may need recalculating. This code deliberately does
  not reopen past days for fees. That is a valid decision but must be written policy, not just a
  code flag.
- **Reconciliation** against the general ledger is disturbed because a signed-off figure moves.

### Regulatory surface

- **CBUAE filings** are as-at a date. A backdated entry that changes an already-filed number is a
  reporting event, not a silent data fix.
- **VAT** — UAE charges 5% on fees. A fee has a tax date. Backdating a fee into a previous month
  touches a filed VAT period and requires a formal VAT amendment.
- **AML** monitoring runs on booking day — what the system saw and when. A backdated entry cannot
  retroactively rerun AML on a closed period.
- **IFRS** — a backdated entry can move revenue or expense recognition across a closed accounting
  period.

### One control before go-live

A hard limit on how far back an entry can be value-dated — for example, three days. Anything
older requires named manager approval with a written reason before it is accepted.

This code has no such limit. An entry can reach back any number of days and silently restate
figures in already-filed periods. One cutoff rule protects VAT, CBUAE filings, AML, and customer
statements all at once — because the damage only happens when an entry reaches far enough back to
cross a closed period. It also bounds the snapshot-invalidation cost from section 1, so one
control protects both correctness and performance.

## 3. Authorization lifecycle

Every ending other than a normal settlement falls into one of two groups: endings the code handles
today, and endings it cannot express.

### Endings the code handles

| Ending | Real-world scenario | System behavior |
|---|---|---|
| Declined at request | Not enough balance when the auth is requested — Auth-B in the event stream | No hold created, DECLINED transition recorded, AUTHORIZATION_DECLINED error logged with the balance that caused it |
| Settled for less | Restaurant pre-auths an estimate including tip, actual bill is lower | Posts the settled amount, releases the full hold; the unused portion returns to available balance without ever posting |
| Settled for more | Hotel adds incidentals on checkout beyond the authorised amount | Posts the full settlement amount, releases the hold, logs OVER_SETTLEMENT for review; never declines — an overdraft here is legitimate and must be visible |
| Second settlement on a closed auth | Merchant retries a settlement already processed | Rejected with AUTHORIZATION_NOT_OPEN, nothing posted |
| Settlement with no matching auth | Settlement message arrives for a reference that was never authorised | Rejected with ORPHAN_SETTLEMENT, nothing posted, no transition created |

### Endings the code cannot handle

| Ending | Real-world scenario | What should happen |
|---|---|---|
| Merchant cancels before settling | Hotel stay cancelled, merchant voids the auth before any settlement | Release the hold immediately; make it idempotent since void messages are routinely retransmitted. Needs a new event type — the current five cannot express a merchant-initiated cancel |
| Auth expires | Online order abandoned, merchant never settles — Auth-B in the event stream is an example | Auto-release after a window set per merchant category; fuel and hospitality have very different windows. Without this, the hold sits forever and understates available balance indefinitely |
| Partial settlement, auth stays open | Split shipment — first box delivered and settled, second still coming | Decrement the hold on each partial settlement and keep the auth open until the hold is exhausted or it expires. The current model closes the auth on the first settlement |
| Account blocked while a hold is live | Fraud freeze or sanctions hit while an approved hold exists | Freeze the hold and require manual disposition; do not auto-release, as releasing on a blocked account is a route for funds to leave |

## 4. What was cut, and the risk each defers

### Would block go-live

| Cut | Production risk |
|---|---|
| No persistence | Everything is in RAM. A restart loses all data. No database, no crash recovery. |
| No double-entry | Only the customer side is posted. No contra entry for fee income or interest expense. Finance cannot use this ledger. |
| No concurrency control | Two authorization requests on the same account at the same time can both pass the balance check and over-commit. Works only because the engine is single-threaded and in-memory. |
| No idempotency on inbound events | A retransmitted event posts twice. Payment networks retransmit routinely. Most likely first production incident. |

### Would cause customer complaints

| Cut | Production risk |
|---|---|
| No hold expiry | A hold that is never settled sits forever and understates available balance indefinitely. Auth-B in our own stream demonstrates this. |
| No back-value limit | Any entry can restate any past period silently — VAT, CBUAE filings, AML, customer statements all affected. Covered in section 2. |
| No business-day calendar | Interest accrues and fees fire on weekends and public holidays. |
| Post-capitalisation interest correction | A reversal arriving after interest has been capitalised leaves an uncorrected error that grows over time. The failing test (`mvn test -Pfailing`) demonstrates this with an AED 0.48 gap. |

