# Worklog

## Phase 1 — Design

Started with the problem brief — two accounts, ten events, six days, with fees and interest. Spent time on clarifying questions and design decisions before writing any code.

Key decisions made upfront:
- Money as whole minor units (no float, no BigDecimal)
- Interest rate as numerator/denominator to keep all math integer-only
- Append-only — nothing is ever edited or deleted
- Fees assessed once per day at close, not retroactively

## Phase 2 — Building the core

Built one file at a time. Order: `CurrencyCode` → `Money` → `Account` → `LedgerEvent` → `Posting` → `AuthorizationTransition` → `Accrual` → `LedgerError` → `Ledger` → `LedgerPolicy` → `LedgerEngine` → `EventStream` → `ReportPrinter` → `Main`.

## Phase 3 — Bugs found and fixed

- `CurrencyCode` had a duplicate field assignment — fixed immediately
- `Comparable<Money>` added by habit, nothing used it — removed
- `available()` was using the wrong balance method — needed the bitemporal form for correct as-at evaluation. Fixed by passing booking day into holds and authorization queries
- 13 test failures because assertions were on the last day of each test window, which is also the capitalisation day — fixed by extending each test window by one day
- Failing test profile did not work with `-Dgroups=failing` — Surefire's `excludedGroups` overrides it. Fixed with a Maven profile

## Phase 4 — Tests and documentation

Wrote six test files covering money arithmetic, ledger queries, engine replay, acceptance criteria, invariants, and the deliberately failing test.

Wrote all documentation: `LLD.md`, `AMBIGUITIES.md`, `NUMBERS.md`, `REJECTED.md`, `ARCHITECTURE.md`, `FAILING_TEST.md`, `README.md`.

## Phase 5 — Cleanup and push

- Pushed to GitHub as `shreshtha235/accountLedgerCore`
- Renamed `reopenClosedDays` to `reopenClosedDaysForFeesCalculation` to be more explicit
- Simplified all docs to plain tables and short English
- Removed Reading B from `Main` — fee is not recalculated for backdated entries, so only one reading is printed
- Trimmed `NUMBERS.md`, `REJECTED.md`, `AMBIGUITIES.md` to remove anything not genuinely useful
