# Failing Test

**File:** `src/test/java/com/ledger/BackValuedInterestTest.java`

**Run it with:**
```bash
mvn test -Pfailing
```

It is excluded from the default suite so the build stays green. It fails by **AED 0.48** and is meant to.

## What it reveals

Interest is calculated once per day at close and the total is posted on the last day. This is correct for a normal stream — but if a correction arrives after that total has already been posted, the ledger has no way to adjust it.

## What it tests

The test removes one of the credits from the event stream and asks: how much interest should have been earned without it?

| | Interest posted |
| --- | --- |
| Original stream (credit included) | AED 0.83 |
| Corrected stream (credit removed) | AED 0.35 |
| Gap | **AED 0.48** |

The ledger already posted 0.83. It cannot take back 0.48 because interest capitalisation is append-only — nothing can be removed or edited.

The posted interest also becomes part of the balance and starts earning interest itself, so the error grows rather than staying fixed.

## Why it is not fixed

Three things are needed, all business decisions not code changes:

1. Recalculate interest for the days the correction affects
2. Post the difference as a new entry on the day the correction arrives
3. A hard cutoff — corrections beyond a certain number of days back are not allowed to reopen past periods

Without the cutoff, a correction arriving years later could silently restate periods that have already been reported and filed.
