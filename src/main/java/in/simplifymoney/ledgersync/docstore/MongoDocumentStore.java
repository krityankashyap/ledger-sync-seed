package in.simplifymoney.ledgersync.docstore;

import com.mongodb.ExplainVerbosity;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Dates;
import in.simplifymoney.ledgersync.store.DocumentStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;

/**
 * MongoDB implementation of the ledger's document store.
 *
 * One document per real transaction. The _id is the transaction's identity
 * (account | direction | amount | instant), so writing the same transaction
 * twice is a no-op - the store is idempotent by construction.
 *
 * The three access patterns are served directly by indexes:
 *   Q1 forAccountMonth  index {account_last4, month, occurred_at_instant desc}
 *   Q2 categoryTotals   $group over the account (index prefix {account_last4})
 *   Q3 byMessageId      multikey index {source_message_ids}
 */
public final class MongoDocumentStore implements DocumentStore, AutoCloseable {

    public static final String DEFAULT_URI = "mongodb://localhost:27018";
    public static final String DEFAULT_DB = "ledger";

    private final MongoClient client;
    private final MongoCollection<Document> txns;

    public MongoDocumentStore() {
        this(DEFAULT_URI, DEFAULT_DB);
    }

    public MongoDocumentStore(String uri, String dbName) {
        this.client = MongoClients.create(uri);
        this.txns = client.getDatabase(dbName).getCollection("transactions");
        txns.createIndex(Indexes.compoundIndex(
                Indexes.ascending("account_last4", "month"),
                Indexes.descending("occurred_at_instant")));
        txns.createIndex(Indexes.ascending("source_message_ids"));
    }

    // ---------------------------------------------------------------- writes

    @Override
    public void save(NormalizedTxn t) {
        Bson filter = Filters.eq("_id", DocumentStore.identity(t));
        Bson update = Updates.combine(
                Updates.set("account_last4", t.accountLast4()),
                Updates.set("month", monthOf(t)),
                Updates.set("occurred_at", t.occurredAt().toString()),
                Updates.set("occurred_at_instant", Date.from(t.occurredAt().toInstant())),
                Updates.set("direction", t.direction().name()),
                Updates.set("amount", new Decimal128(t.amount())),
                Updates.set("category", t.category().name()),
                Updates.set("merchant", t.merchant()),
                Updates.addEachToSet("source_message_ids", t.sourceMessageIds()));
        txns.updateOne(filter, update, new UpdateOptions().upsert(true));
    }

    // --------------------------------------------------------------- queries

    /** Q1: one account's transactions for one month, newest first. */
    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        List<NormalizedTxn> out = new ArrayList<>();
        for (Document d : txns.find(Filters.and(
                        Filters.eq("account_last4", accountLast4),
                        Filters.eq("month", monthString(month))))
                .sort(Sorts.descending("occurred_at_instant"))) {
            out.add(fromDoc(d));
        }
        return out;
    }

    /** Q2: running totals per category for an account, whole history. */
    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Map<Category, BigDecimal> totals = new EnumMap<>(Category.class);
        for (Category c : Category.values()) totals.put(c, ZERO);
        for (Document d : txns.aggregate(List.of(
                Aggregates.match(Filters.eq("account_last4", accountLast4)),
                Aggregates.group("$category", Accumulators.sum("total", "$amount"))))) {
            totals.put(Category.valueOf(d.getString("_id")), toBigDecimal(d.get("total")));
        }
        return totals;
    }

    /** Q3: which transaction, if any, did this message produce? */
    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        Document d = txns.find(Filters.eq("source_message_ids", messageId)).first();
        return Optional.ofNullable(d).map(MongoDocumentStore::fromDoc);
    }

    // --------------------------------------------------------------- helpers

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    /** The identity of a transaction, independent of which messages reported it. */
    static String idOf(NormalizedTxn t) {
        return t.accountLast4() + "|" + t.direction().name() + "|"
                + t.amount().toPlainString() + "|" + t.occurredAt().toInstant();
    }

    private static String monthOf(NormalizedTxn t) {
        OffsetDateTime ist = t.occurredAt().withOffsetSameInstant(Dates.IST);
        return String.format("%04d-%02d", ist.getYear(), ist.getMonthValue());
    }

    private static String monthString(YearMonth m) {
        return String.format("%04d-%02d", m.getYear(), m.getMonthValue());
    }

    private static BigDecimal toBigDecimal(Object mongoNumber) {
        if (mongoNumber instanceof Decimal128 d) {
            return d.bigDecimalValue().setScale(2, RoundingMode.HALF_UP);
        }
        return new BigDecimal(String.valueOf(mongoNumber)).setScale(2, RoundingMode.HALF_UP);
    }

    private static NormalizedTxn fromDoc(Document d) {
        List<String> ids = new ArrayList<>(d.getList("source_message_ids", String.class));
        Collections.sort(ids);
        return new NormalizedTxn(
                d.getString("account_last4"),
                OffsetDateTime.parse(d.getString("occurred_at")),
                Direction.valueOf(d.getString("direction")),
                toBigDecimal(d.get("amount")),
                Category.valueOf(d.getString("category")),
                d.getString("merchant"),
                ids);
    }

    // ------------------------------------------------- benchmarking / admin

    /** Fast unordered insert of new documents. Used by the 100k benchmark. */
    public void bulkLoad(List<NormalizedTxn> batch) {
        List<Document> docs = new ArrayList<>(batch.size());
        for (NormalizedTxn t : batch) docs.add(toDoc(t));
        txns.insertMany(docs, new InsertManyOptions().ordered(false));
    }

    /** {examined, returned} for each query, from explain executionStats. */
    public long[] statsForAccountMonth(String account, YearMonth month) {
        Bson f = Filters.and(Filters.eq("account_last4", account),
                Filters.eq("month", monthString(month)));
        Document ex = txns.find(f).sort(Sorts.descending("occurred_at_instant"))
                .explain(ExplainVerbosity.EXECUTION_STATS);
        return new long[]{findLong(ex, "totalDocsExamined"), txns.countDocuments(f)};
    }

    public long[] statsCategoryTotals(String account) {
        List<Bson> pipe = List.of(
                Aggregates.match(Filters.eq("account_last4", account)),
                Aggregates.group("$category", Accumulators.sum("total", "$amount")));
        Document ex = txns.aggregate(pipe).explain(ExplainVerbosity.EXECUTION_STATS);
        long returned = 0;
        for (Document ignored : txns.aggregate(pipe)) returned++;
        return new long[]{findLong(ex, "totalDocsExamined"), returned};
    }

    public long[] statsByMessageId(String messageId) {
        Bson f = Filters.eq("source_message_ids", messageId);
        Document ex = txns.find(f).explain(ExplainVerbosity.EXECUTION_STATS);
        return new long[]{findLong(ex, "totalDocsExamined"), txns.countDocuments(f)};
    }

    /** First value for `key` anywhere in an explain document (executionStats is nested). */
    private static long findLong(Object node, String key) {
        if (node instanceof Document doc) {
            if (doc.containsKey(key) && doc.get(key) instanceof Number n) return n.longValue();
            for (Object v : doc.values()) {
                long r = findLong(v, key);
                if (r >= 0) return r;
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) {
                long r = findLong(v, key);
                if (r >= 0) return r;
            }
        }
        return -1;
    }

    private static Document toDoc(NormalizedTxn t) {
        return new Document("_id", DocumentStore.identity(t))
                .append("account_last4", t.accountLast4())
                .append("month", monthOf(t))
                .append("occurred_at", t.occurredAt().toString())
                .append("occurred_at_instant", Date.from(t.occurredAt().toInstant()))
                .append("direction", t.direction().name())
                .append("amount", new Decimal128(t.amount()))
                .append("category", t.category().name())
                .append("merchant", t.merchant())
                .append("source_message_ids", t.sourceMessageIds());
    }

    /** Everything currently stored, for consistency checking. */
    public List<NormalizedTxn> all() {
        List<NormalizedTxn> out = new ArrayList<>();
        for (Document d : txns.find()) out.add(fromDoc(d));
        return out;
    }

    public long count() {
        return txns.countDocuments();
    }

    /** Wipe the collection (used by the 100k benchmark, not the pipeline). */
    public void drop() {
        txns.drop();
        txns.createIndex(Indexes.compoundIndex(
                Indexes.ascending("account_last4", "month"),
                Indexes.descending("occurred_at_instant")));
        txns.createIndex(Indexes.ascending("source_message_ids"));
    }

    @Override
    public void close() {
        client.close();
    }
}
