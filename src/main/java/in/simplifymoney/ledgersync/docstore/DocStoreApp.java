package in.simplifymoney.ledgersync.docstore;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CLI for the document store (Task 4). Kept out of App so the corpus pipeline
 * (and verify.sh) never has to compile against the Mongo driver.
 *
 *   backfill              SQL -> Mongo, deduped and idempotent
 *   check                 report where SQL and Mongo disagree
 *   bench [n]             load n synthetic txns, print examined-vs-returned
 *   query <acct> <YYYY-MM>  Q1 + Q2 for one account
 *   msg <messageId>       Q3: which transaction did this message produce
 */
public final class DocStoreApp {

    private static final Path DB = Path.of("data", "ledger");

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: backfill | check | bench [n] | query <acct> <YYYY-MM> | msg <id>");
            System.exit(2);
        }
        switch (args[0]) {
            case "backfill" -> backfill();
            case "check" -> check();
            case "bench" -> bench(args.length > 1 ? Integer.parseInt(args[1]) : 100_000);
            case "query" -> query(args[1], YearMonth.parse(args[2]));
            case "msg" -> msg(args[1]);
            default -> {
                System.err.println("unknown command: " + args[0]);
                System.exit(2);
            }
        }
    }

    private static void backfill() {
        try (SqlLedgerStore sql = new SqlLedgerStore(DB);
             MongoDocumentStore mongo = new MongoDocumentStore()) {
            Backfill.Result r = new Backfill(sql, mongo).run();
            System.out.printf("backfill: read %d, written %d, skipped (duplicates) %d%n",
                    r.read(), r.written(), r.skipped());
            System.out.println("mongo documents now: " + mongo.count());
        }
    }

    private static void check() {
        try (SqlLedgerStore sql = new SqlLedgerStore(DB);
             MongoDocumentStore mongo = new MongoDocumentStore()) {
            List<ConsistencyChecker.Divergence> d = new ConsistencyChecker(sql, mongo).check();
            if (d.isEmpty()) {
                System.out.println("stores agree: no divergences");
            } else {
                System.out.println(d.size() + " divergence(s):");
                for (ConsistencyChecker.Divergence x : d) {
                    System.out.printf("  %s%n     sql: %s%n     doc: %s%n",
                            x.what(), x.inSql(), x.inDocuments());
                }
            }
        }
    }

    private static void query(String account, YearMonth month) {
        try (MongoDocumentStore mongo = new MongoDocumentStore()) {
            List<NormalizedTxn> rows = mongo.forAccountMonth(account, month);
            System.out.printf("%s %s: %d transactions (newest first)%n", account, month, rows.size());
            rows.stream().limit(5).forEach(t -> System.out.printf("  %s %-8s %10s %s%n",
                    t.occurredAt(), t.direction(), t.amount().toPlainString(), t.merchant()));
            System.out.println("category totals: " + mongo.categoryTotals(account));
        }
    }

    private static void msg(String messageId) {
        try (MongoDocumentStore mongo = new MongoDocumentStore()) {
            mongo.byMessageId(messageId).ifPresentOrElse(
                    t -> System.out.printf("%s -> %s %s %s on %s, cited by %s%n",
                            messageId, t.accountLast4(), t.direction(), t.amount().toPlainString(),
                            t.occurredAt(), t.sourceMessageIds()),
                    () -> System.out.println(messageId + " -> no transaction"));
        }
    }

    private static void bench(int n) {
        // A throwaway database so the benchmark never touches the real ledger.
        try (MongoDocumentStore mongo =
                     new MongoDocumentStore(MongoDocumentStore.DEFAULT_URI, "ledger_bench")) {
            System.out.println("loading " + n + " synthetic transactions...");
            mongo.drop();

            OffsetDateTime base = OffsetDateTime.parse("2026-01-01T00:00:00+05:30");
            Category[] cats = Category.values();
            List<NormalizedTxn> batch = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String acct = (i % 2 == 0) ? "4821" : "9075";
                OffsetDateTime when = base.plusMinutes(i);            // unique instant per row
                BigDecimal amt = new BigDecimal(100 + (i % 9000)).setScale(2);
                Direction dir = (i % 7 == 0) ? Direction.CREDIT : Direction.DEBIT;
                Category cat = cats[(i / 2) % cats.length]; // all 4 per account
                batch.add(new NormalizedTxn(acct, when, dir, amt, cat, "BENCH",
                        List.of("m-bench-" + i)));
                if (batch.size() == 5000) { mongo.bulkLoad(batch); batch.clear(); }
            }
            if (!batch.isEmpty()) mongo.bulkLoad(batch);

            YearMonth month = YearMonth.from(base.plusMinutes(n / 2));
            long[] q1 = mongo.statsForAccountMonth("4821", month);
            long[] q2 = mongo.statsCategoryTotals("4821");
            long[] q3 = mongo.statsByMessageId("m-bench-" + (n / 2));

            System.out.printf("%nAt %d transactions:%n", mongo.count());
            System.out.printf("  %-42s examined %8d  returned %8d%n",
                    "Q1 forAccountMonth(4821, " + month + ")", q1[0], q1[1]);
            System.out.printf("  %-42s examined %8d  returned %8d%n",
                    "Q2 categoryTotals(4821)", q2[0], q2[1]);
            System.out.printf("  %-42s examined %8d  returned %8d%n",
                    "Q3 byMessageId(m-bench-" + (n / 2) + ")", q3[0], q3[1]);
        }
    }
}
