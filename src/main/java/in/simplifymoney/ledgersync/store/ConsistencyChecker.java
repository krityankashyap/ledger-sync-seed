package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * Both stores are reduced to one entry per transaction identity (the SQL side
 * is deduped the way Backfill dedupes it, so its historical duplicates are not
 * reported as differences). Then, identity by identity:
 *
 *  - in SQL but not in the document store   -> a transaction went missing
 *  - in the document store but not in SQL   -> an extra/altered transaction
 *    (a changed amount shows up as the original missing + a new one appearing,
 *     because amount is part of identity)
 *  - in both but a field differs            -> category, merchant or evidence
 *    was altered
 *
 * This compares values, not counts - a store with the same number of rows but
 * one amount changed is still caught.
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        Map<String, NormalizedTxn> inSql = dedup(sql.all());
        Map<String, NormalizedTxn> inDocs = dedup(documents.all());

        List<Divergence> out = new ArrayList<>();

        for (Map.Entry<String, NormalizedTxn> e : inSql.entrySet()) {
            NormalizedTxn s = e.getValue();
            NormalizedTxn d = inDocs.get(e.getKey());
            if (d == null) {
                out.add(new Divergence(e.getKey(), describe(s), "(missing)"));
            } else {
                String diff = fieldDiff(s, d);
                if (diff != null) out.add(new Divergence(e.getKey() + " [" + diff + "]",
                        describe(s), describe(d)));
            }
        }
        for (Map.Entry<String, NormalizedTxn> e : inDocs.entrySet()) {
            if (!inSql.containsKey(e.getKey())) {
                out.add(new Divergence(e.getKey(), "(missing)", describe(e.getValue())));
            }
        }
        return out;
    }

    /** Collapse to one entry per transaction identity, merging cited messages. */
    private static Map<String, NormalizedTxn> dedup(List<NormalizedTxn> rows) {
        Map<String, NormalizedTxn> byId = new LinkedHashMap<>();
        for (NormalizedTxn t : rows) {
            byId.merge(DocumentStore.identity(t), t, (a, b) -> {
                TreeSet<String> ids = new TreeSet<>(a.sourceMessageIds());
                ids.addAll(b.sourceMessageIds());
                return new NormalizedTxn(a.accountLast4(), a.occurredAt(), a.direction(),
                        a.amount(), a.category(), a.merchant(), new ArrayList<>(ids));
            });
        }
        return byId;
    }

    /** Account, direction, amount and instant already match (they are the key). */
    private static String fieldDiff(NormalizedTxn s, NormalizedTxn d) {
        List<String> diffs = new ArrayList<>();
        if (s.category() != d.category()) {
            diffs.add("category " + s.category() + "!=" + d.category());
        }
        if (!s.merchant().equals(d.merchant())) {
            diffs.add("merchant '" + s.merchant() + "'!='" + d.merchant() + "'");
        }
        if (!new TreeSet<>(s.sourceMessageIds()).equals(new TreeSet<>(d.sourceMessageIds()))) {
            diffs.add("source_message_ids " + s.sourceMessageIds() + "!=" + d.sourceMessageIds());
        }
        return diffs.isEmpty() ? null : String.join("; ", diffs);
    }

    private static String describe(NormalizedTxn t) {
        return t.category() + " " + t.amount().toPlainString() + " " + t.merchant()
                + " ids=" + t.sourceMessageIds();
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
