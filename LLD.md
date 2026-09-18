# Low-Level Design — Account Ledger Core

## 1. Scope

An in-memory, append-only, bitemporal account ledger core, exercised by a runnable replay
and a JUnit suite. It reports, per day per account: closing ledger balance, available
balance, overdraft fee assessments, interest accruals, authorization states, and errors.

**Non-goals.** No web layer, no persistence, no UI, no database, no dependency-injection
framework, no double-entry general ledger, no currency conversion. Every omission is listed
in section 12 with the production risk it defers.

## 2. Build and runtime

| Item | Choice | Reason |
| --- | --- | --- |
| Language | Java, `--release 21` | Records and sealed interfaces with exhaustive switches are load-bearing here. 21 is the current LTS, so the grader does not need JDK 25. |
| Build | Maven 3.9 | `mvn test` is one universally understood command. |
| Tests | JUnit 5, test scope only | — |
| Runtime dependencies | none | Nothing outside `java.base` is needed, and a money core with a dependency tree is a liability. |

```
mvn -q test                      # the suite (green)
mvn -q test -Dgroups=failing     # the one deliberately failing test
mvn -q compile exec:java         # the six-day replay printout
```

## 3. The four decisions that shape everything

**3.1 Every posting carries two dates.** `bookingDay` is when the system learned of the
entry; `valueDay` is when it counts towards a balance. One balance function takes a
value-date cutoff and, optionally, a "known as of" booking day. The overdraft check, the
availability test, the day close and the report all call that same function. Nothing keeps
its own notion of the balance. This is the actual problem being tested.

**3.2 Money is whole minor units in a value type.** `Money` holds a `long` of minor units
(fils for AED, thousandths for BHD) plus its currency. No `double`, no `float`, nothing
fractional ever stored. Division happens in exactly two places — the daily accrual and the
instalment split — and both route through one static half-up helper on `Money`. Every binary
operation asserts matching currency, so adding AED to BHD is unrepresentable rather than
merely discouraged.

**3.3 The ledger is four append-only lists and nothing else.** Postings, authorization
transitions, accruals and errors. Each list is private with an unmodifiable view out and
append as the only mutator. State that looks mutable — an authorization's current state, a
day's closing balance — is derived by querying those lists, never stored back. There is no
second representation of state anywhere in the program, which is why there are no snapshot
or result objects.

**3.4 Only the rulings that are actually exercised are configurable.** Six policy fields,
not twelve. Every other ambiguity was decided once, is a named constant in the engine, and is
documented in `AMBIGUITIES.md`. Documenting a decision and making it configurable are
different things.

**3.5 Nothing about this particular event stream is baked into the engine.** The day window,
the interest rate and the capitalisation day are all inputs. The six days, the 0.04% and the
two accounts of the brief live in `EventStream` as a fixture, so replaying a different stream
of a different length over different accounts needs no change to the engine.

## 4. Files

One package, `com.ledger`. Fifteen production files, six test files.

```
accountLedgerCore/
  pom.xml  .gitignore
  LLD.md  README.md  NUMBERS.md  AMBIGUITIES.md  REJECTED.md  WORKLOG.md
  src/main/java/com/ledger/
    CurrencyCode.java              enum AED(2), BHD(3)
    Money.java                     record(long minor, CurrencyCode) + half-up rounding
    Account.java                   record(String id, CurrencyCode currency, Money opening)
    LedgerEvent.java               sealed interface + 5 nested records
    Posting.java                   record + nested Type enum
    AuthorizationTransition.java   record + nested State enum
    Accrual.java                   record(String accountId, int day, Money amount)
    LedgerError.java               record + nested Code enum
    Ledger.java                    the four append-only lists + every query
    LedgerPolicy.java              4 fields + nested ResidualPolicy enum
    LedgerEngine.java              replay, event application, day close, fee, interest
    EventStream.java               the ten events and two accounts from the brief
    ReportPrinter.java             plain text, reads straight off the Ledger
    Main.java                      runs the replay under both readings of the fee rule
  src/test/java/com/ledger/
    MoneyTest.java                 arithmetic, parsing, currency guard, rounding
    LedgerTest.java                balance queries, bitemporal query, append-only
    LedgerEngineTest.java          authorizations, settlement, reversal, instalments, day close
    AcceptanceCriteriaTest.java    all eight criteria, including the four refused
    InvariantTest.java             summation, one-fee-per-day, determinism, instalment shortfall
    BackValuedInterestTest.java    @Tag("failing") — the annotated failing test
```

## 5. Data model

### Money

```java
public record Money(long minor, CurrencyCode currency) implements Comparable<Money> {
    static Money of(String decimal, CurrencyCode c);   // "1,200.00" -> 120000 fils
    static Money ofMinor(long minor, CurrencyCode c);
    static Money zero(CurrencyCode c);
    static long roundHalfUp(long numerator, long denominator);

    Money plus(Money other);          // asserts same currency
    Money minus(Money other);
    Money negated();
    Money applyRate(long numerator, long denominator);   // rounds half-up, once
    List<Money> divideEqually(int parts);                // floor each part; caller derives residual
    boolean isNegative();  // strictly < 0
    boolean isPositive();  // strictly > 0
    boolean isZero();
    String format();       // "AED -370.00", "BHD 10.000"
}
```

`of` parses against the currency's own scale and rejects extra decimals, so
`Money.of("10.0000", BHD)` fails loudly instead of truncating. `roundHalfUp` is sign-aware
half-away-from-zero, implemented on integers, and is the only rounding in the codebase.

`divideEqually` returns exactly `parts` floored amounts; the caller computes the residual as
`total.minus(sum)`. That keeps the residual a derived fact rather than an extra return type.

### Events — what the outside world tells us

```java
public sealed interface LedgerEvent {
    String id(); int bookingDay(); String accountId(); int valueDay();

    record Credit   (String id, int bookingDay, String accountId, Money amount,
                     int valueDay, int instalments)                   implements LedgerEvent {}
    record Debit    (String id, int bookingDay, String accountId, Money amount,
                     int valueDay)                                    implements LedgerEvent {}
    record Authorize(String id, int bookingDay, String accountId, String authRef,
                     Money amount, int valueDay)                      implements LedgerEvent {}
    record Settle   (String id, int bookingDay, String accountId, String authRef,
                     Money amount, int valueDay)                      implements LedgerEvent {}
    record Reversal (String id, int bookingDay, String accountId, String reversesEventId,
                     int valueDay)                                    implements LedgerEvent {}
}
```

There is no hold event. A hold is not something the outside world sends — `Authorize` carries
the amount and, if approved, *produces* a hold. Holds are derived from the transition log.
Events are what we were told; transitions are what we decided.

`instalments` is a field on `Credit` rather than a sixth record, because splitting is a
posting concern, not a different kind of business event. `Reversal` keeps its own `accountId`
and `valueDay` even though both are derivable from the target, so a reversal that disagrees
with the event it reverses becomes a logged error rather than a silently applied posting.

### The four things we write

```java
record Posting(long seq, String eventId, String accountId, Money amount,
               int valueDay, int bookingDay, Type type) {
    enum Type { OPENING_BALANCE, CREDIT, DEBIT, SETTLEMENT, REVERSAL,
                OVERDRAFT_FEE, INTEREST_CAPITALISATION, FEE_REFUND }
}

record AuthorizationTransition(String authRef, String accountId, Money amount,
                               int day, State toState, String reason) {
    enum State { APPROVED, DECLINED, SETTLED, RELEASED }
}

record Accrual(String accountId, int day, Money amount) {}

record LedgerError(int day, String eventId, Code code, String detail) {
    enum Code { ORPHAN_SETTLEMENT, AUTHORIZATION_DECLINED, OVER_SETTLEMENT,
                INSTALMENT_RESIDUAL_DISCARDED, FEE_CURRENCY_UNDEFINED,
                REVERSAL_TARGET_NOT_FOUND, DUPLICATE_REVERSAL }
}
```

Debits and fees are stored as **negative** amounts, so a balance is a plain sum and there is
no sign convention to remember at each call site. `seq` is a monotonic counter, used for
deterministic ordering and for the append-only prefix check in tests.

An orphan settlement produces no transition at all, because there is no authorization to
transition — only an error. That distinction is what acceptance criterion four gets wrong.

Accruals are their own list, not postings. An accrual is not a ledger entry until it
capitalises, and putting them in the posting list would mean every balance query had to
remember to filter them out.

## 6. Ledger

Constructed from the accounts, so it knows each account's currency and can seed the
`OPENING_BALANCE` postings itself. That removes the only special case the balance function
would otherwise need.

```java
public final class Ledger {
    Ledger(List<Account> accounts);

    void post(Posting p);
    void record(AuthorizationTransition t);
    void record(Accrual a);
    void record(LedgerError e);

    List<Posting> postings();                       // unmodifiable, all four are
    List<AuthorizationTransition> transitions();
    List<Accrual> accruals();
    List<LedgerError> errors();

    Money balance(String accountId, int valueDayCutoff);
    Money balance(String accountId, int valueDayCutoff, int knownByBookingDay);
    Money activeHolds(String accountId);
    Money available(String accountId, int day);     // balance minus active holds
    Optional<State> stateOf(String authRef);
    Map<String, State> authorizationsFor(String accountId);
    boolean feeAssessed(String accountId, int day);
    Optional<Money> feeOn(String accountId, int day);
    Money accrual(String accountId, int day);
    Money accrualTotal(String accountId);
    Optional<Money> capitalisationFor(String accountId);
    List<LedgerError> errorsOn(int day);
}
```

`balance` sums postings where `valueDay <= cutoff`, and for the three-argument overload also
`bookingDay <= knownByBookingDay`. That second form is what makes "what did Day 2 look like
at the end of Day 5" answerable, which acceptance criterion one asks for directly.

Every scan is linear over all postings. At ten events that is free; the call site carries a
comment naming the ceiling and pointing at snapshot invalidation as the production fix, so
the trade-off is visible in the code and not only in a document.

## 7. Engine

```java
public final class LedgerEngine {
    LedgerEngine(List<Account> accounts, LedgerPolicy policy);
    Ledger replay(List<LedgerEvent> events, int firstDay, int lastDay);
    Ledger replay(List<LedgerEvent> events);   // window derived from the events themselves
}
```

Nothing about this particular stream is a constant. The window is a parameter, and the
interest rate comes from the policy. The single-argument overload derives the window from the
events — earliest of any booking or value day to the latest — which is what a caller with an
arbitrary stream wants. The brief's six days are supplied explicitly by `EventStream`, because
the brief fixes that window whether or not the events happen to fill it.

### Replay

```
events sorted stably by bookingDay        // listed order preserved within a day
for day in firstDay..lastDay:
    apply every event with bookingDay == day, in order
    closeDay(day)                         // fee, then accrual
    if day == lastDay: capitalise(day)
return ledger
```

The stable sort matters: E10 is a Day 5 event listed after E9, a Day 6 event, so the brief's
list is not in day order. Sorting by booking day while preserving listed order inside a day is
the only reading under which both "replayed in this order" and the stated booking days hold.

### Per event

| Event | Behaviour |
| --- | --- |
| `Credit` | `divideEqually(instalments)`, one posting per part at the event's value day. Any residual is applied per `instalmentResidual` and **always** recorded as `INSTALMENT_RESIDUAL_DISCARDED`, so a dropped fraction is never silent. |
| `Debit` | One negative posting at the event's value day. |
| `Authorize` | `available = balance(account, currentDay) - activeHolds(account)`. Approve only if `available - amount >= 0`. Record `APPROVED` or `DECLINED`; a decline also writes `AUTHORIZATION_DECLINED` so it appears in the day's output. No posting either way. |
| `Settle` | Look up the auth ref in the **transition log**, not the postings. Absent → `ORPHAN_SETTLEMENT`, no posting, funds do not move. Present and approved → negative posting for the settled amount, then `SETTLED`, which releases the whole remaining hold. Settling for more than was held posts in full and raises `OVER_SETTLEMENT` for review rather than declining, because declining would hide a real unauthorised overdraft. |
| `Reversal` | Resolve the target event id. Missing → `REVERSAL_TARGET_NOT_FOUND`. Already reversed → `DUPLICATE_REVERSAL` and no second posting, so replaying a reversal is idempotent. Otherwise mirror every posting of the target with a negated amount, the **same value day**, and today's booking day. With `refundFeeOnReversal` set, also emit `FEE_REFUND` for any fee whose day is no longer negative. |

### Day close

Order is itself a decision. The fee lands in the same day's closing balance and can take a day
from positive to negative, which zeroes that day's accrual — and on Day 5 it does exactly that.

```
for each account:
    closing = balance(account, day)
    if closing.isNegative() && !feeAssessed(account, day):
        fee = policy.feeFor(account.currency())        // absent -> FEE_CURRENCY_UNDEFINED, no fee
        post(OVERDRAFT_FEE, fee.negated(), valueDay = day, bookingDay = day)

    afterFee = balance(account, day)                   // recomputed, not reused
    accrual  = afterFee.isPositive()
             ? afterFee.applyRate(policy.rateNumerator(), policy.rateDenominator())
             : Money.zero(currency)
    record(new Accrual(account, day, accrual))
```

Zero accruals are recorded explicitly rather than skipped, so the accrual log has one row per
account per day and the capitalised credit reconciles row by row.

`feeAssessed` is a query over the postings rather than a separate flag, which is what makes it
safe to assess a day more than once when `reopenClosedDays` is on.

### Capitalisation

On the last day of the window, after that day's accrual:

```
total = accrualTotal(account)      // exact sum of stored, already-rounded values
if !total.isZero():
    post(INTEREST_CAPITALISATION, total, valueDay = lastDay, bookingDay = lastDay)
```

The "rounded accruals must sum exactly to the capitalized total" rule therefore holds by
construction — the capitalised figure is *defined* as that sum. There is no remainder and
nothing to discard, which is why acceptance criterion eight is refused.

## 8. Policy

```java
public record LedgerPolicy(long rateNumerator,
                           long rateDenominator,
                           Map<CurrencyCode, Money> overdraftFee,
                           ResidualPolicy instalmentResidual,
                           boolean reopenClosedDays,
                           boolean refundFeeOnReversal) {
    enum ResidualPolicy { DISCARD, FIRST, LAST }
    static LedgerPolicy defaults();    // 4/10000, {AED: 25.00}, DISCARD, false, false
    Optional<Money> feeFor(CurrencyCode currency);
}
```

The rate is held as a numerator over a denominator rather than a decimal, so 0.04% is
`4 / 10_000` exactly and there is no decimal type anywhere in the interest path. The
denominator must be positive and the numerator non-negative; both are checked at construction.

`overdraftFee` has **no BHD entry, deliberately**. The brief states the fee in AED but ACC-002
is a BHD account and no exchange rate is supplied anywhere. If a BHD account ever closes
negative, the engine records `FEE_CURRENCY_UNDEFINED` and charges nothing rather than guessing.
The current stream never reaches that path, which is itself worth stating: this exercise cannot
catch a wrong answer there.

Decided once, constant, documented in `AMBIGUITIES.md` but not configurable: half-up rounding;
accrual runs on every day of the window; accrue before capitalising; exactly zero is not
negative; events within a day are evaluated in listed order; the unused part of a hold is
released in full on settlement; there is no backdating limit; opening balances are postings
rather than a starting number.

## 9. Output

`ReportPrinter` reads straight off the `Ledger` — there is no intermediate snapshot type,
because the ledger already holds every figure and a second copy could only drift from it.

Per day, per account: closing ledger balance, available balance, active holds, fee assessed,
accrual stored, interest capitalised on the last day, every authorization with its current
state, then that day's errors. Available balance and holds are printed even though the brief
does not ask for them, because they are the only place a hold is visible and without them a
reader cannot see why an authorization was declined.

`Main` runs the same stream twice — under `LedgerPolicy.defaults()` and again with
`reopenClosedDays = true` — and prints both, so the two readings of the overdraft rule can be
compared rather than argued about.

### Target figures under the default policy

To be confirmed by execution, not asserted from hand arithmetic:

- ACC-001 daily closings: 250.00, 250.00, 650.00, 465.00, −180.00, 440.00
- One fee, value day 5. Accruals 0.10, 0.10, 0.26, 0.19, 0.00, 0.18. Capitalised 0.83. Final 440.83
- ACC-002 closings 0.000 on days 1–4, then 9.999. Accruals 0.004 on days 5 and 6. Capitalised 0.008. Final 10.007
- Auth-A `SETTLED`, Auth-B `DECLINED`, Auth-Z never exists
- Errors: orphan settlement on day 4, declined authorization on day 5, discarded residual on day 5

## 10. Test plan

**`MoneyTest`** — parsing including thousands separators and rejection of over-precision,
arithmetic, the currency guard on every binary operation, strict zero semantics, formatting at
both scales, and `roundHalfUp` at the exact half in both signs as well as the four real accrual
cases.

**`LedgerTest`** — balance by value date, the bitemporal "as at end of day N" query, active
holds, and the append-only property.

**`LedgerEngineTest`** — authorization approve and decline, settlement against a valid
authorization and an orphan one, reversal including the duplicate guard, instalment splitting,
fee assessment and its once-per-day cap, and the accrual being computed after the fee.

**`AcceptanceCriteriaTest`** — one test per criterion. For the four refused, the test asserts
the value the design holds to be correct and carries an inline comment naming why the stated
criterion fails. That makes `REJECTED.md` executable rather than merely claimed.

**`InvariantTest`** — stored accruals sum exactly to the capitalised posting for every account;
at most one fee per account per day; the posting list only grows and no existing element is ever
rewritten, checked by comparing prefixes across a re-run; two replays produce identical output;
and the instalment sum invariant, which under the `DISCARD` ruling **fails by BHD 0.001** and is
reported as a measured shortfall rather than skipped.

**`BackValuedInterestTest`** — appends a Day 7 reversal of the Day 3 credit and asserts the
capitalised interest matches what the corrected history earned. It fails, because the design
treats Day 6 as a hard close and has no back-value adjustment path. The annotation states that
this is the edge of the chosen model rather than a defect in the code, and that fixing it needs
a policy decision about a back-value cutoff, not a code change. Tagged `failing` and excluded
from the default run so the suite stays green while the gap stays visible and runnable.

## 11. Build order

One commit per step, no squashing, each message carrying the reasoning rather than the diff.

1. `pom.xml`, `.gitignore`, package skeleton — **done**
2. `CurrencyCode`, `Money` + `MoneyTest`
3. `Account`, `LedgerEvent`, `Posting`, `AuthorizationTransition`, `Accrual`, `LedgerError`
4. `Ledger` + `LedgerTest`
5. `LedgerPolicy`, `EventStream`
6. `LedgerEngine` — event application, one type at a time, tested as each lands
7. `LedgerEngine` — day close, fee, interest, capitalisation + tests
8. `ReportPrinter`, `Main`
9. `AcceptanceCriteriaTest` — all eight
10. `InvariantTest`
11. `BackValuedInterestTest`
12. `Dockerfile` — so the suite and the replay run with no local JDK or Maven setup
13. `README`, `NUMBERS`, `AMBIGUITIES`, `REJECTED`, `WORKLOG`

`WORKLOG.md` is written as the work happens, with real times, and should line up with the commit
timestamps. It will contain wrong turns — including the discarded reopen-history fee design and
an earlier version of this document that had twenty-eight types across six packages — rather
than a tidied narrative.

## 12. Deliberately not built

No persistence, so no durability and replay time grows with history. No concurrency control, so
in a real deployment two authorizations on one account could both pass the availability test. No
inbound idempotency keys, so a retransmitted event would post twice. No period close or
back-value limit. No post-capitalisation interest correction, which the failing test
demonstrates. No business-day calendar, so interest accrues on non-working days. No currency
conversion. No hold expiry, and no `EXPIRED` or `PARTIALLY_SETTLED` authorization states, since
nothing in the stream reaches them. No explicit authorization void event from the acquirer. No
double-entry contra postings, so the ledger does not balance in the accounting sense and could
not be handed to finance. No access control or maker-checker on manual entries. No metrics.

Each appears in `REJECTED.md` with the risk it defers.
