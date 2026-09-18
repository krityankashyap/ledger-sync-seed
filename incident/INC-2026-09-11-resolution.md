# INC-2026-09-11 — resolution

**Status:** RESOLVED

## Five lines for the channel

1. **What broke:** the SMS amount regex required two decimals, so whole-rupee amounts (`Rs.5`, `INR 18,000`) didn't match and the parser took the *next* rupee figure in the message — the `Avl Bal` — as the transaction amount.
2. **How we found it:** reproduced the customer's SMS as a unit test — `Amounts.first("Rs.5 ... Avl Bal: Rs.92,213.10")` returned `92213.10`, not `5.00` — matching `app.log`'s `amount_extracted=92213.10`.
3. **Who was affected:** any transaction whose amount was a whole number of rupees with a balance quoted — **38 messages in corpus-a**, across all accounts (e.g. the ₹5 `UPI/WATER CAN` on **4821** booked as ₹92,213.10, which fired `ledger.balance.divergence`).
4. **The fix:** made the paise optional — `([0-9,]+(?:\.[0-9]{2})?)` — so `find()` stops at the real amount and never reaches the balance.
5. **Why it can't recur:** `AmountsTest.wholeRupeeAmountIsNotConfusedWithTheBalance` (plus the `INR 18,000` case) fails before the fix and passes after; the suite was green through the incident only because every earlier fixture used amounts with two decimals.

## Why the tests were green the whole time

Every pre-existing `AmountsTest` case used a two-decimal amount (`2,499.50`, `333.33`, `45,000.00`). The bug only triggers on whole-rupee amounts, which no test exercised — so the suite never saw it. The lesson: test the boundary the format allows (no paise), not just the common case.
