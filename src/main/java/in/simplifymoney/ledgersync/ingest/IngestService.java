package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * This is the naive version. It parses each message on its own and saves
 * whatever comes back. It does not ask whether two messages describe the same
 * transaction, and it decides the category from the direction alone.
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        int skipped = 0;
    
        // Group every parsed message by the transaction it evidences.
        Map<TxnKey, List<ParsedTxn>> groups = new LinkedHashMap<>();
        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            ParsedTxn t = p.get();
            groups.computeIfAbsent(TxnKey.of(t), k -> new ArrayList<>()).add(t);
        }
    
        for (List<ParsedTxn> group : groups.values()) {
            store.save(merge(group));
        }
        return new Stats(messages.size(), groups.size(), skipped);
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    private NormalizedTxn merge(List<ParsedTxn> group) {
        ParsedTxn first = group.get(0);
        List<String> ids = group.stream()
                .map(ParsedTxn::sourceMessageId)
                .distinct()
                .sorted()
                .toList();
        Category c = first.direction() == Direction.DEBIT ? Category.SPEND : Category.INCOME;
        return new NormalizedTxn(first.accountLast4(), first.occurredAt(), first.direction(),
                first.amount(), c, first.merchant(), ids);
    }

       /** Identifies one real transaction, independent of which message reported it. */
       private record TxnKey(String accountLast4, Direction direction,
        BigDecimal amount, OffsetDateTime occurredAt) {
static TxnKey of(ParsedTxn p) {
return new TxnKey(p.accountLast4(), p.direction(), p.amount(), p.occurredAt());
}
}

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}
