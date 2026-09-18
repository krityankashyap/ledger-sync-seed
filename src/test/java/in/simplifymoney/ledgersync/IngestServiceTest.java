package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deduplication: many messages, one transaction.
 *
 * The same transaction can arrive as an SMS, an email, and a re-uploaded SMS.
 * They must collapse to a single ledger entry citing every message that
 * evidenced it - even when the email's Date: header is in a different timezone.
 */
class IngestServiceTest {

    // The same UBER debit, as three messages. The SMS quotes IST (00:20 on the
    // 19th); the email's header is UTC (18:50 on the 18th) - the SAME instant.
    private static final String SMS =
            "{\"message_id\":\"m-sms\",\"channel\":\"sms\",\"sender\":\"AD-HDFCBK-S\","
            + "\"received_at\":\"2026-07-19T00:25:00+05:30\",\"device_id\":\"dev-1\","
            + "\"body\":\"Rs 412.67 debited from a/c **4821 on 19-07-26 at 00:20 "
            + "to UBER INDIA. Avl Bal: Rs.70,891.55.\"}";

    private static final String EMAIL_UTC =
            "{\"message_id\":\"m-email\",\"channel\":\"email\",\"sender\":\"alerts@hdfcbank.net\","
            + "\"received_at\":\"2026-07-19T01:00:00+05:30\",\"device_id\":\"dev-1\","
            + "\"body\":\"Date: Sat, 18 Jul 2026 18:50:00 +0000\\nSubject: alert\\n\\n"
            + "Your account ending 4821 has been debited with INR 412.67.\\n"
            + "Merchant / Remarks: UBER INDIA\\nTransaction reference: 4190129089\"}";

    private static final String SMS_REUPLOAD =
            "{\"message_id\":\"m-sms-reupload\",\"channel\":\"sms\",\"sender\":\"AD-HDFCBK-S\","
            + "\"received_at\":\"2026-08-01T10:00:00+05:30\",\"device_id\":\"dev-1\","
            + "\"body\":\"Rs 412.67 debited from a/c **4821 on 19-07-26 at 00:20 "
            + "to UBER INDIA. Avl Bal: Rs.70,891.55.\"}";

    private static List<NormalizedTxn> ingest(Path dir, String... lines) throws Exception {
        Path corpus = dir.resolve("corpus.jsonl");
        Files.writeString(corpus, String.join("\n", lines));
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        new IngestService(new Parsers(), store).ingestFile(corpus);
        return store.all();
    }

    @Test
    void smsEmailAndReuploadCollapseToOneTransaction(@TempDir Path dir) throws Exception {
        List<NormalizedTxn> ledger = ingest(dir, SMS, EMAIL_UTC, SMS_REUPLOAD);

        assertEquals(1, ledger.size(), "three messages, one transaction");
        assertEquals(List.of("m-email", "m-sms", "m-sms-reupload"),
                ledger.get(0).sourceMessageIds(),
                "the entry must cite all three messages, sorted");
    }

    @Test
    void utcEmailMergesWithIstSms(@TempDir Path dir) throws Exception {
        // The offset differs (+0000 vs +05:30) but the instant is identical.
        // Keying on the instant is what makes these merge.
        List<NormalizedTxn> ledger = ingest(dir, SMS, EMAIL_UTC);

        assertEquals(1, ledger.size(),
                "an email in UTC and its SMS in IST are the same transaction");
    }

    @Test
    void differentTransactionsStaySeparate(@TempDir Path dir) throws Exception {
        String other =
                "{\"message_id\":\"m-other\",\"channel\":\"sms\",\"sender\":\"AD-HDFCBK-S\","
                + "\"received_at\":\"2026-07-19T09:00:00+05:30\",\"device_id\":\"dev-1\","
                + "\"body\":\"Rs 500.00 debited from a/c **4821 on 19-07-26 at 09:00 "
                + "to SWIGGY. Avl Bal: Rs.70,391.55.\"}";

        List<NormalizedTxn> ledger = ingest(dir, SMS, other);

        assertEquals(2, ledger.size(), "different amounts are different transactions");
    }
}
