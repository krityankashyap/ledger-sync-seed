package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * The SQL store is not clean - it has been running without a uniqueness
 * guarantee, so it holds duplicate rows for the same transaction. We collapse
 * them by transaction identity ({@link DocumentStore#identity}), merging the
 * message ids each duplicate cites, and write each transaction once.
 *
 * Safe to run more than once, including after a partial failure: the target's
 * save() is an idempotent upsert keyed by that same identity, so re-running (or
 * resuming after a crash) converges to exactly one document per transaction.
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> rows = source.all();

        // Collapse SQL duplicates by transaction identity, merging their evidence.
        Map<String, NormalizedTxn> distinct = new LinkedHashMap<>();
        for (NormalizedTxn t : rows) {
            distinct.merge(DocumentStore.identity(t), t, Backfill::mergeEvidence);
        }

        long written = 0;
        for (NormalizedTxn t : distinct.values()) {
            target.save(t);   // idempotent upsert - re-running changes nothing
            written++;
        }
        return new Result(rows.size(), written, rows.size() - written);
    }

    /** Same transaction, two rows: keep one, union the message ids (sorted). */
    private static NormalizedTxn mergeEvidence(NormalizedTxn a, NormalizedTxn b) {
        TreeSet<String> ids = new TreeSet<>(a.sourceMessageIds());
        ids.addAll(b.sourceMessageIds());
        return new NormalizedTxn(a.accountLast4(), a.occurredAt(), a.direction(),
                a.amount(), a.category(), a.merchant(), new ArrayList<>(ids));
    }

    public record Result(long read, long written, long skipped) {}
}
