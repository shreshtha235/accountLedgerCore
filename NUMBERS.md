# Numbers

Every constant in the codebase and why that value.

| Constant | Value | Where | Why |
| --- | --- | --- | --- |
| Overdraft fee | AED 25.00 | `LedgerPolicy.defaults()` | Given by the brief. Stored in policy so it can be changed without touching the engine. |
| Interest rate | 0.04% per day, stored as `4 / 10_000` | `LedgerPolicy.defaults()` | Given by the brief. Stored as two integers so the math stays integer-only — no rounding until the final step. |
| AED scale | 2 decimal places | `CurrencyCode.AED` | ISO 4217 standard for the dirham. |
| BHD scale | 3 decimal places | `CurrencyCode.BHD` | ISO 4217 standard for the dinar. |
| Money stored as minor units | AED 1,200.00 → `120000` | `Money` | No decimal point in memory. `double` cannot represent 0.1 exactly — fine over 6 days, wrong over years. `BigDecimal` needs a scale set at every operation; forgetting gives a silently wrong number. Minor units make fractional values unrepresentable, not just discouraged. |
| Rounding | Half away from zero | `Money.roundHalfUp` | Used in exactly two places: daily accrual and instalment split. Half-to-even is also valid but harder to explain to a customer querying a fil. Only matters when a value lands exactly on a half — nothing in this stream does, so the wrong choice would not be caught, which is why it is written down. |
| Day close order | Fee first, then accrue | `LedgerEngine.closeDay` | Fee is value-dated the same day so it sits inside the closing balance. A day closing at +20.00 with a 25.00 fee ends overdrawn and earns nothing. Interest-first would accrue on a day the customer ended in overdraft. |
| No BHD fee entry | Empty | `LedgerPolicy.defaults()` | Brief states fee in AED, no exchange rate given. Charging BHD 25.000 would be ~10x the intended penalty. Failing with `FEE_CURRENCY_UNDEFINED` is the only honest option. |
