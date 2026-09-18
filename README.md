# Account Ledger Core

An in-memory, append-only, bitemporal account ledger. It replays an event stream and reports,
per day per account, the closing ledger balance, the available balance, overdraft fee
assessments, interest accruals, authorization states, and errors.

No web layer, no persistence, no database, no UI, and no runtime dependency outside
`java.base`. JUnit 5 is test scope only.

## Running it

### Without Docker — needs Java 21+ and Maven only

```bash
mvn test
```

```bash
mvn test -Pfailing
```

```bash
mvn compile exec:java
```

The first runs the suite — 68 tests, all green. The second runs the one deliberately failing
test, which is tagged and excluded from the default suite; it is meant to fail, and the failure
message reports the gap it exposes. The third prints the six-day replay.

### With Docker — needs no local JDK or Maven

```bash
docker build -t account-ledger-core .
```

```bash
docker run --rm account-ledger-core
```

The image build runs the suite, so a successful build is itself proof the tests pass. The
container then prints the replay.

## Reading the output

Every entry in this ledger carries two dates, and nearly everything interesting in the exercise
comes from the gap between them:

- **booking day** — the day the system was told about the entry
- **value day** — the day it counts towards a balance

E7 is the clearest case: booked on Day 5, value-dated Day 2. It changes what Day 2 through Day 5
closed at, three days after those days had already closed.

So each day prints the balance up to twice:

```
closing ledger balance (at close)     AED -180.00
closing ledger balance (restated now) AED 440.00   <-- changed by a later backdated entry
```

The first line is what the day closed at on the evidence available then, and it is the figure
the overdraft fee was assessed against. The second appears only when a later backdated entry
changed it. Day 5 shows both because E9, on Day 6, reversed E7 back on Day 2.

A fee also names its booking day when that differs from its value day, for example
`AED 25.00   (booked on day 5)`, so a fee discovered after the fact explains itself.

`available balance` and `active holds` are printed even though the brief does not ask for them.
They are the only place a hold is visible — a hold never touches the ledger balance — and
without them you cannot see why an authorization was declined.

The run prints the same stream twice, under the two readings of the overdraft rule:

- **Reading A** (default) — a day is assessed once, on that day, and never reopened. One fee, on
  Day 5. ACC-001 closes at **AED 440.83**.
- **Reading B** — a backdated entry reopens earlier days for assessment. Three fees, on Days 2,
  4 and 5. ACC-001 closes at **AED 390.81**.

Both are printed rather than one being chosen, because the brief does not settle which is
intended. See `AMBIGUITIES.md` entry 1.

ACC-002 closes at **BHD 10.007** under either reading.

## What it found

Three errors are recorded across the window, all of them business outcomes rather than crashes:

| Day | Event | Code | What happened |
| --- | --- | --- | --- |
| 4 | E6 | `ORPHAN_SETTLEMENT` | Auth-Z has no authorization; rejected, no money moved |
| 5 | E8 | `AUTHORIZATION_DECLINED` | Auth-B declined — available was already −155.00 before the 90.00 hold |
| 5 | E10 | `INSTALMENT_RESIDUAL_DISCARDED` | BHD 10.000 split three ways leaves 0.001, discarded |

Auth-B being **declined** is worth flagging. The brief says it is never settled inside the
window, which reads as though it stayed open — but by the time it arrives, E7 has landed and the
ledger is already below zero, so the availability rule refuses it.

Four of the eight stated acceptance criteria are refused, and one is right in intent but wrong
as worded. `REJECTED.md` gives the reasoning, and `AcceptanceCriteriaTest` asserts each
refusal, so the reasoning is executable rather than only written down.

## Layout

Fifteen production files in one package, six test files.

| File | Role |
| --- | --- |
| `Money`, `CurrencyCode` | exact arithmetic on whole minor units, and the only rounding in the codebase |
| `Account`, `LedgerEvent` | the accounts, and the five things the outside world can say |
| `Posting`, `AuthorizationTransition`, `Accrual`, `LedgerError` | the four things that get written, all append-only |
| `Ledger` | those four lists plus every query, including the bitemporal balance |
| `LedgerPolicy` | the six configurable rulings, including the rate and the per-currency fee |
| `LedgerEngine` | replay, the five event handlers, day close, fee, interest, capitalisation |
| `EventStream` | the brief's ten events and two accounts, in the brief's order |
| `ReportPrinter`, `Main` | the only output, read straight off the ledger |

Nothing about this particular stream is baked into the engine. The day window is a parameter,
the rate and fee come from the policy, and capitalisation happens on the last day of whatever
window is given — so a longer stream over different accounts in different currencies needs no
engine change.

## The other documents

- `LLD.md` — the design and why it is shaped this way
- `AMBIGUITIES.md` — 31 ambiguities found in the brief, how each was resolved, and what would
  change if it were reversed
- `NUMBERS.md` — every constant, separated into those the brief fixed and those I chose
- `REJECTED.md` — the refused acceptance criteria with the arithmetic, and the approaches
  abandoned mid-build
- `WORKLOG.md` — what happened when, wrong turns included
