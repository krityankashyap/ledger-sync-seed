package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.ingest.Categories;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Categorisation rules.
 *
 * MICRO  = a UPI debit of Rs.100 or less.
 * TRANSFER = a debit on one account matched by a credit on the OTHER account
 *            (same amount, close in time). Decided from the money, not the text.
 */
class CategoriesTest {

    private static NormalizedTxn txn(String acct, String when, Direction dir,
                                     String amount, String merchant) {
        return new NormalizedTxn(acct, OffsetDateTime.parse(when), dir,
                new BigDecimal(amount), Category.SPEND, merchant, List.of("m-" + when + amount));
    }

    /** Re-categorise and return category keyed by merchant, for easy asserting. */
    private static Map<String, Category> byMerchant(List<NormalizedTxn> in) {
        return Categories.categorize(in).stream()
                .collect(Collectors.toMap(NormalizedTxn::merchant, NormalizedTxn::category));
    }

    @Test
    void crossAccountPairIsMarkedTransferOnBothLegs() {
        // 4821 sends 5000 to 9075: a debit on one, a credit on the other, ~1 min apart.
        var out = byMerchant(List.of(
                txn("4821", "2026-08-01T14:21:00+05:30", Direction.DEBIT, "5000.00", "OUT LEG"),
                txn("9075", "2026-08-01T14:22:00+05:30", Direction.CREDIT, "5000.00", "IN LEG")));

        assertEquals(Category.TRANSFER, out.get("OUT LEG"));
        assertEquals(Category.TRANSFER, out.get("IN LEG"));
    }

    @Test
    void upiDebitOf100OrLessIsMicro() {
        var out = byMerchant(List.of(
                txn("4821", "2026-07-04T07:19:00+05:30", Direction.DEBIT, "5.00", "UPI/WATER CAN"),
                txn("9075", "2026-07-04T08:00:00+05:30", Direction.DEBIT, "100.00", "UPI/CHAIWALA")));

        assertEquals(Category.MICRO, out.get("UPI/WATER CAN"));
        assertEquals(Category.MICRO, out.get("UPI/CHAIWALA")); // exactly 100 counts
    }

    @Test
    void smallNonUpiDebitStaysSpend() {
        // Rs.47.33 to SWIGGY is under 100 but NOT UPI -> ordinary spend, not micro.
        var out = byMerchant(List.of(
                txn("4821", "2026-07-02T11:14:00+05:30", Direction.DEBIT, "47.33", "SWIGGY")));

        assertEquals(Category.SPEND, out.get("SWIGGY"));
    }

    @Test
    void upiDebitOver100StaysSpend() {
        var out = byMerchant(List.of(
                txn("4821", "2026-07-02T11:14:00+05:30", Direction.DEBIT, "100.01", "UPI/KIRANA")));

        assertEquals(Category.SPEND, out.get("UPI/KIRANA"));
    }

    @Test
    void loneCreditWithNoMatchingDebitStaysIncome() {
        // "NEFT INWARD SELF" says SELF but has no second leg on the other account,
        // so it is money arriving from elsewhere -> INCOME, not TRANSFER.
        var out = byMerchant(List.of(
                txn("9075", "2026-07-01T21:14:00+05:30", Direction.CREDIT, "18000.00", "NEFT INWARD SELF")));

        assertEquals(Category.INCOME, out.get("NEFT INWARD SELF"));
    }

    @Test
    void sameAmountSameAccountIsNotATransfer() {
        // Two 500 debits on the SAME account are not a transfer (needs two accounts).
        var out = Categories.categorize(List.of(
                txn("4821", "2026-07-01T10:00:00+05:30", Direction.DEBIT, "500.00", "A"),
                txn("4821", "2026-07-01T10:05:00+05:30", Direction.CREDIT, "500.00", "B")));

        for (NormalizedTxn t : out) {
            assertEquals(t.direction() == Direction.DEBIT ? Category.SPEND : Category.INCOME,
                    t.category(), "same-account pair must not become TRANSFER");
        }
    }
}
