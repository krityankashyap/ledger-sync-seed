package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Finds transactions the ledger cannot account for.
 *
 * Every bank SMS quotes the balance after the transaction (Avl Bal). Walk each
 * account's transactions in time order: the stated balance must move by exactly
 * the transaction amount. Where it moves by more, an unrecorded transaction
 * happened between the two messages - money the ledger cannot explain.
 */
public final class Reconciliation {

    private Reconciliation() {}

    public static Map<String, Object> check(Path corpus) throws IOException {
        // Parse + dedupe by instant, keeping a representative that carries the
        // stated balance whenever any of its messages did (emails do not).
        Map<String, ParsedTxn> uniq = new LinkedHashMap<>();
        Parsers parsers = new Parsers();
        for (RawMessage m : IngestService.readCorpus(corpus)) {
            if (m.body().contains("Avl Limit")) continue; // credit card: a limit is not a balance
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) continue;
            ParsedTxn t = p.get();
            String key = t.accountLast4() + "|" + t.direction() + "|"
                    + t.amount() + "|" + t.occurredAt().toInstant();
            uniq.merge(key, t, (kept, incoming) ->
                    kept.statedBalance() != null ? kept : incoming);
        }

        // Group by account.
        Map<String, List<ParsedTxn>> byAccount = new TreeMap<>();
        for (ParsedTxn t : uniq.values()) {
            byAccount.computeIfAbsent(t.accountLast4(), k -> new ArrayList<>()).add(t);
        }

        List<Object> discrepancies = new ArrayList<>();
        for (List<ParsedTxn> txns : byAccount.values()) {
            txns.sort(Comparator.comparing(ParsedTxn::occurredAt));

            ParsedTxn prev = null;
            for (ParsedTxn t : txns) {
                if (prev != null
                        && prev.statedBalance() != null
                        && t.statedBalance() != null) {
                    BigDecimal expected = prev.statedBalance().add(signed(t));
                    BigDecimal gap = t.statedBalance().subtract(expected);
                    if (gap.signum() != 0) {
                        discrepancies.add(discrepancy(prev, t, gap));
                    }
                }
                prev = t;
            }
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("discrepancies", discrepancies);
        return doc;
    }

    private static BigDecimal signed(ParsedTxn t) {
        return t.direction() == Direction.DEBIT ? t.amount().negate() : t.amount();
    }

    private static Map<String, Object> discrepancy(ParsedTxn prev, ParsedTxn t, BigDecimal gap) {
        String kind = gap.signum() < 0 ? "debit" : "credit";
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("account_last4", t.accountLast4());
        d.put("occurred_at", t.occurredAt().toString());
        d.put("amount", gap.abs().toPlainString());
        d.put("note", "unexplained " + kind + " of " + gap.abs().toPlainString()
                + " between " + prev.occurredAt() + " and " + t.occurredAt()
                + ": bank balance moved with no message to account for it");
        return d;
    }
}