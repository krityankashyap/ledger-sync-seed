package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.report.Reconciliation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reconciliation: catching what the ledger cannot account for.
 *
 * Every bank SMS quotes the balance after the transaction. Where consecutive
 * stated balances move by more than the transactions between them explain, a
 * transaction happened with no message - a discrepancy.
 */
class ReconciliationTest {

    /** An HDFC savings SMS: a debit of `amount`, leaving `avlBal`. */
    private static String savingsDebit(String id, String amount, String hhmm,
                                       String merchant, String avlBal) {
        return "{\"message_id\":\"" + id + "\",\"channel\":\"sms\",\"sender\":\"AD-HDFCBK-S\","
                + "\"received_at\":\"2026-07-01T" + hhmm + ":00+05:30\",\"device_id\":\"dev-1\","
                + "\"body\":\"Rs " + amount + " debited from a/c **4821 on 01-07-26 at " + hhmm
                + " to " + merchant + ". Avl Bal: Rs." + avlBal + ".\"}";
    }

    /** An HDFC credit-card SMS: a spend, leaving `avlLimit` (a limit, not a balance). */
    private static String cardSpend(String id, String amount, String hhmm,
                                    String merchant, String avlLimit) {
        return "{\"message_id\":\"" + id + "\",\"channel\":\"sms\",\"sender\":\"AD-HDFCBK-S\","
                + "\"received_at\":\"2026-07-01T" + hhmm + ":00+05:30\",\"device_id\":\"dev-1\","
                + "\"body\":\"Rs " + amount + " spent on HDFC Bank Card x3310 at " + merchant
                + " on 01-07-26 " + hhmm + ". Avl Limit: Rs." + avlLimit + ".\"}";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> discrepancies(Path dir, String... lines) throws Exception {
        Path corpus = dir.resolve("corpus.jsonl");
        Files.writeString(corpus, String.join("\n", lines));
        Map<String, Object> doc = Reconciliation.check(corpus);
        return (List<Map<String, Object>>) (List<?>) doc.get("discrepancies");
    }

    @Test
    void aConsistentBalanceChainHasNoDiscrepancies(@TempDir Path dir) throws Exception {
        // 900.00 - 50.00 = 850.00, exactly what the next message states.
        List<Map<String, Object>> found = discrepancies(dir,
                savingsDebit("m-1", "100.00", "10:00", "SWIGGY", "900.00"),
                savingsDebit("m-2", "50.00", "11:00", "ZOMATO", "850.00"));

        assertTrue(found.isEmpty(), "a consistent chain has nothing to report");
    }

    @Test
    void anUnaccountedBalanceDropIsReported(@TempDir Path dir) throws Exception {
        // After m-1 the balance is 900.00. m-2 is a 50.00 debit but the balance
        // is 550.00 - it should be 850.00. 300.00 left with no message.
        List<Map<String, Object>> found = discrepancies(dir,
                savingsDebit("m-1", "100.00", "10:00", "SWIGGY", "900.00"),
                savingsDebit("m-2", "50.00", "11:00", "ZOMATO", "550.00"));

        assertEquals(1, found.size(), "the missing 300 must be reported");
        assertEquals("4821", found.get(0).get("account_last4"));
        assertEquals("300.00", found.get(0).get("amount"));
    }

    @Test
    void creditCardLimitIsNotReconciled(@TempDir Path dir) throws Exception {
        // The limit moves by more than the spends (a bill payment credited the
        // card), but a credit limit is not a cash balance, so it is not reported.
        List<Map<String, Object>> found = discrepancies(dir,
                cardSpend("m-1", "100.00", "10:00", "BLINKIT", "5000.00"),
                cardSpend("m-2", "50.00", "11:00", "DMART", "4000.00"));

        assertTrue(found.isEmpty(), "a credit card's Avl Limit is not reconciled");
    }
}
