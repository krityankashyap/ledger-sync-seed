package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.EmailParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Bank alert email parsing.
 *
 * Both banks use one body shape. The transaction time comes from the "Date:"
 * header, not from when the email arrived.
 */
class EmailParserTest {

    private final EmailParser parser = new EmailParser();

    /** Build an alert email the way the corpus does. arrivedAt is receivedAt. */
    private static RawMessage email(String sender, OffsetDateTime arrivedAt, String body) {
        return new RawMessage("m-test", "email", sender, arrivedAt, "dev-test", body);
    }

    @Test
    void readsHdfcCreditWithWholeRupeeAmount() {
        Optional<ParsedTxn> p = parser.parse(email("alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-01T09:47:00+05:30"),
                "Date: Wed, 01 Jul 2026 09:02:00 +0530\n"
                        + "Subject: Transaction alert on your account\n\n"
                        + "Dear Customer,\n\n"
                        + "Your account ending 4821 has been credited with INR 45,000.\n"
                        + "Merchant / Remarks: SALARY CREDIT\n"
                        + "Transaction reference: 1597155421\n\n"
                        + "This is a system generated email."));

        assertTrue(p.isPresent(), "an HDFC alert email should parse");
        ParsedTxn t = p.get();
        assertEquals("4821", t.accountLast4());
        assertEquals(Direction.CREDIT, t.direction());
        assertEquals(new BigDecimal("45000.00"), t.amount());
        assertEquals("SALARY CREDIT", t.merchant());
    }

    @Test
    void readsIciciDebit() {
        Optional<ParsedTxn> p = parser.parse(email("alerts@icicibank.com",
                OffsetDateTime.parse("2026-07-06T11:32:00+05:30"),
                "Date: Mon, 06 Jul 2026 11:25:00 +0530\n"
                        + "Subject: Transaction alert on your account\n\n"
                        + "Dear Customer,\n\n"
                        + "Your account ending 9075 has been debited with Rs.129.67.\n"
                        + "Merchant / Remarks: RELIANCE SMART\n"
                        + "Transaction reference: 6576810104\n\n"
                        + "This is a system generated email."));

        assertTrue(p.isPresent(), "an ICICI alert email should parse");
        assertEquals(Direction.DEBIT, p.get().direction());
        assertEquals(new BigDecimal("129.67"), p.get().amount());
        assertEquals("9075", p.get().accountLast4());
    }

    @Test
    void occurredAtComesFromTheHeaderNotTheArrivalTime() {
        OffsetDateTime arrived = OffsetDateTime.parse("2026-07-01T09:47:00+05:30");
        Optional<ParsedTxn> p = parser.parse(email("alerts@hdfcbank.net", arrived,
                "Date: Wed, 01 Jul 2026 09:02:00 +0530\n\n"
                        + "Your account ending 4821 has been credited with INR 45,000.\n"
                        + "Merchant / Remarks: SALARY CREDIT\n"));

        assertTrue(p.isPresent());
        // The transaction happened at 09:02 (header), not 09:47 (arrival).
        assertEquals(OffsetDateTime.parse("2026-07-01T09:02:00+05:30"), p.get().occurredAt());
        assertNotEquals(arrived, p.get().occurredAt());
    }

    @Test
    void ignoresANonTransactionEmail() {
        Optional<ParsedTxn> p = parser.parse(email("alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-01T09:47:00+05:30"),
                "Date: Wed, 01 Jul 2026 09:02:00 +0530\n"
                        + "Subject: Your e-statement is ready\n\n"
                        + "Dear Customer, your monthly statement can now be downloaded."));

        assertTrue(p.isEmpty(), "a non-transaction email is not a transaction");
    }
}
