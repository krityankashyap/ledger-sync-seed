package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import in.simplifymoney.ledgersync.docstore.MongoDocumentStore;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test against a real MongoDB (docker compose up).
 *
 * Skipped automatically when Mongo is not reachable, so `gradle test` stays
 * green with no container running.
 */
class MongoDocumentStoreIT {

    // Fail fast if the container is not up, so the test skips instead of hanging.
    private static final String URI =
            "mongodb://localhost:27018/?serverSelectionTimeoutMS=1500";

    private MongoDocumentStore store;

    private static NormalizedTxn txn(String acct, String iso, Direction dir,
                                     String amount, Category cat, String merchant, String... ids) {
        return new NormalizedTxn(acct, OffsetDateTime.parse(iso), dir,
                new BigDecimal(amount), cat, merchant, List.of(ids));
    }

    @BeforeEach
    void setUp() {
        MongoDocumentStore s;
        try {
            s = new MongoDocumentStore(URI, "ledger_it");
            s.drop(); // also proves connectivity
        } catch (RuntimeException e) {
            assumeTrue(false, "MongoDB not reachable - skipping (start it with docker compose up)");
            return;
        }
        this.store = s;
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void savesIdempotentlyAndServesTheThreeQueries() {
        // Same transaction twice, cited by different messages -> one doc, both ids.
        store.save(txn("4821", "2026-07-04T20:24:00+05:30", Direction.DEBIT,
                "2499.50", Category.SPEND, "AMAZON PAY", "m-1"));
        store.save(txn("4821", "2026-07-04T20:24:00+05:30", Direction.DEBIT,
                "2499.50", Category.SPEND, "AMAZON PAY", "m-2"));
        store.save(txn("4821", "2026-07-01T09:02:00+05:30", Direction.CREDIT,
                "45000.00", Category.INCOME, "SALARY CREDIT", "m-3"));
        store.save(txn("9075", "2026-07-05T08:00:00+05:30", Direction.DEBIT,
                "30.00", Category.MICRO, "UPI/CHAIWALA", "m-4"));

        assertEquals(3, store.count(), "the duplicate save must not create a second doc");

        // Q1: one account, one month, newest first.
        List<NormalizedTxn> july = store.forAccountMonth("4821", YearMonth.of(2026, 7));
        assertEquals(2, july.size());
        assertTrue(july.get(0).occurredAt().isAfter(july.get(1).occurredAt()),
                "newest first");

        // Q2: category totals for the account.
        Map<Category, BigDecimal> totals = store.categoryTotals("4821");
        assertEquals(new BigDecimal("2499.50"), totals.get(Category.SPEND));
        assertEquals(new BigDecimal("45000.00"), totals.get(Category.INCOME));
        assertEquals(new BigDecimal("0.00"), totals.get(Category.TRANSFER));

        // Q3: which transaction did a message produce? (via the merged id)
        Optional<NormalizedTxn> byM2 = store.byMessageId("m-2");
        assertTrue(byM2.isPresent());
        assertEquals("4821", byM2.get().accountLast4());
        assertEquals(new BigDecimal("2499.50"), byM2.get().amount());
        assertEquals(List.of("m-1", "m-2"), byM2.get().sourceMessageIds());
    }

    @Test
    void reRunningChangesNothing() {
        NormalizedTxn t = txn("4821", "2026-07-04T20:24:00+05:30", Direction.DEBIT,
                "2499.50", Category.SPEND, "AMAZON PAY", "m-1");
        store.save(t);
        store.save(t);
        store.save(t);
        assertEquals(1, store.count(), "the same transaction saved thrice is one doc");
    }
}
