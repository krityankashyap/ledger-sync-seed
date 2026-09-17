package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.IciciSmsParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ICICI SMS parsing.
 *
 * Format 1: "Dear Customer, Acct XX.. is debited with INR .. Info: .."
 * Format 2: "ICICI Bank Acct XX.. Dr/Cr INR .. on dd-MMM-yyyy HH:mm; MERCHANT ref no .."
 */
class IciciSmsParserTest {

    private final IciciSmsParser parser = new IciciSmsParser();

    /** Build an ICICI SMS the way the corpus does. */
    private static RawMessage sms(String body) {
        return new RawMessage("m-test", "sms", IciciSmsParser.SENDER,
                OffsetDateTime.parse("2026-08-12T09:00:00+05:30"), "dev-test", body);
    }

    @Test
    void readsFormatTwoDebit() {
        Optional<ParsedTxn> p = parser.parse(sms(
                "ICICI Bank Acct XX9075 Dr INR 99.99 on 12-Aug-2026 08:40; "
                        + "APOLLO PHARMACY ref no 427426739493. BalAvl Rs 52,220.31"));

        assertTrue(p.isPresent(), "format 2 debit should parse");
        ParsedTxn t = p.get();
        assertEquals("9075", t.accountLast4());
        assertEquals(Direction.DEBIT, t.direction());
        assertEquals(new BigDecimal("99.99"), t.amount());
        assertEquals("APOLLO PHARMACY", t.merchant());
        assertEquals(OffsetDateTime.parse("2026-08-12T08:40:00+05:30"), t.occurredAt());
    }

    @Test
    void formatTwoCrMapsToCredit() {
        Optional<ParsedTxn> p = parser.parse(sms(
                "ICICI Bank Acct XX9075 Cr INR 5000.00 on 01-Aug-2026 14:22; "
                        + "IMPS/P2A/PARAG KAPOOR ref no 908129880743. BalAvl Rs 52,928.02"));

        assertTrue(p.isPresent(), "format 2 credit should parse");
        assertEquals(Direction.CREDIT, p.get().direction());
        assertEquals(new BigDecimal("5000.00"), p.get().amount());
    }

    @Test
    void formatTwoReadsWholeRupeeAmount() {
        Optional<ParsedTxn> p = parser.parse(sms(
                "ICICI Bank Acct XX9075 Dr INR 5 on 23-Jul-2026 18:41; "
                        + "UPI/BARBER ref no 154245459403. BalAvl Rs 52,841.30"));

        assertTrue(p.isPresent());
        assertEquals(new BigDecimal("5.00"), p.get().amount());
    }

    @Test
    void stillReadsFormatOne() {
        Optional<ParsedTxn> p = parser.parse(sms(
                "Dear Customer, Acct XX9075 is debited with INR 22.50 on "
                        + "01/07/2026 10:22. Info: UPI/VEGETABLE VENDOR. Avl Bal Rs.31,882.25 -ICICI Bank"));

        assertTrue(p.isPresent(), "format 1 must still parse");
        assertEquals(Direction.DEBIT, p.get().direction());
        assertEquals(new BigDecimal("22.50"), p.get().amount());
        assertEquals("UPI/VEGETABLE VENDOR", p.get().merchant());
    }

    @Test
    void ignoresAdverts() {
        Optional<ParsedTxn> p = parser.parse(sms(
                "Get a pre-approved Personal Loan of upto Rs.5,00,000 at 10.5% p.a. "
                        + "Click to know more. T&C apply. -ICICI Bank"));

        assertTrue(p.isEmpty(), "adverts are not transactions");
    }
}
