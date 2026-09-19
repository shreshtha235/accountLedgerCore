# Account Ledger Core

An in-memory, append-only, account ledger.

## Running

### With Docker

```bash
docker build -t account-ledger-core .
```

```bash
docker run --rm account-ledger-core
```

The build runs all tests. A successful build means all tests pass. The container then prints the replay.

### Without Docker — needs Java 21+ and Maven

**Install Java 21 and Maven (one-time setup)**

Mac:
```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```
After Homebrew finishes it prints two lines starting with `eval` — run those, then:
```bash
brew install maven
brew install --cask temurin@21
```

Windows — download and install both:
- Java 21: https://adoptium.net/en-GB/temurin/releases/?version=21
- Maven: https://maven.apache.org/download.cgi (then add its `bin` folder to PATH — guide: https://maven.apache.org/install.html)

Linux:
```bash
sudo apt install maven openjdk-21-jdk
```

Verify both are installed:
```bash
mvn -version
java -version
```

```bash
mvn test
```

```bash
mvn test -Pfailing
```

```bash
mvn compile exec:java
```

`mvn test` — runs all tests, should be green.

`mvn test -Pfailing` — runs the one deliberately failing test. It is meant to fail and the message explains the gap.

`mvn compile exec:java` — **prints the six-day event stream replay.** This is the main output.

`mvn test -Dtest=SampleStreamsTest` — runs five additional sample event streams with different scenarios.

## Reading the output

### Event stream

The replay runs two accounts over six days. Key events:

| Day | Event | What happened |
| --- | --- | --- |
| 1 | Credit AED 1,200 → ACC-001 | Opening credit |
| 2 | Debit AED 950 from ACC-001 | Withdrawal |
| 2 | Auth-A hold AED 200 on ACC-001 | Card pre-authorization |
| 3 | Credit AED 400 → ACC-001 | Deposit |
| 4 | Settle Auth-A for AED 185 | Card transaction completed |
| 4 | E6 — settle unknown Auth-Z | Rejected — no such authorization |
| 5 | Debit AED 620 backdated to Day 2 | Arrived Day 5, counts from Day 2 |
| 5 | Auth-B hold AED 90 on ACC-001 | Declined — account already overdrawn |
| 5 | Credit BHD 10 in 3 instalments → ACC-002 | Split evenly; 0.001 residual discarded |
| 6 | Reversal of Day 5 debit | Cancels the 620 debit; fee already charged stays |

### Sample output

```
================================================================================
DAY 2
================================================================================
  ACC-001  AED
    closing ledger balance (at close)     AED 250.00
    available balance                     AED  50.00
    active holds                          AED 200.00
    overdraft fee assessed                none
    interest accrued                      AED 0.10
    authorizations                        Auth-A APPROVED
  errors
    none

================================================================================
DAY 5
================================================================================
  ACC-001  AED
    closing ledger balance (at close)     AED -180.00
    closing ledger balance (restated now) AED  440.00   <-- changed by a later backdated entry
    available balance                     AED -180.00
    active holds                          AED 0.00
    overdraft fee assessed                AED 25.00
    interest accrued                      AED 0.00
    authorizations                        Auth-A SETTLED, Auth-B DECLINED
  errors
    E8   AUTHORIZATION_DECLINED          Auth-B declined: available AED -155.00 minus hold AED 90.00 is AED -245.00
    E10  INSTALMENT_RESIDUAL_DISCARDED   Splitting BHD 10.000 into 3 equal parts leaves BHD 0.001, discarded
```

### Output fields

| Field | Example value | Meaning |
| --- | --- | --- |
| closing ledger balance (at close) | AED 250.00 | Sum of all postings value-dated up to this day, as known when the day closed |
| closing ledger balance (restated now) | AED 440.00 | Same sum recalculated today — appears only when a later backdated entry changed it |
| available balance | AED 50.00 | Ledger balance minus active holds — what can actually be spent |
| active holds | AED 200.00 | Money reserved for a pending transaction; not posted to ledger yet |
| overdraft fee assessed | AED 25.00 | Charged when closing balance went below zero |
| interest accrued | AED 0.10 | Daily interest on closing balance; all days accumulate and post together on the last day |
| interest capitalised | AED 0.83 | Total interest posted on the last day — equals sum of all daily accruals |
| authorizations | Auth-A APPROVED | State of card pre-authorizations: APPROVED / SETTLED / DECLINED |
| errors | AUTHORIZATION_DECLINED | Business rejections — nothing crashes, reason is logged with the event |

ACC-001 closes at **AED 440.83**. ACC-002 closes at **BHD 10.007**.
