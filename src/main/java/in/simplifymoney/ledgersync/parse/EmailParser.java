package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails.
 *
 * Both HDFC and ICICI send the same body shape:
 *
 *   Date: Wed, 01 Jul 2026 09:02:00 +0530
 *   ...
 *   Your account ending 4821 has been credited with INR 45,000.
 *   Merchant / Remarks: SALARY CREDIT
 *   Transaction reference: 1597155421
 *
 * occurredAt comes from the "Date:" header, NOT from RawMessage.receivedAt:
 * the header is when the bank says the transaction happened, and it is what
 * lets an email line up with the SMS for the same transaction.
 */
public final class EmailParser implements MessageParser {

    private static final Pattern DATE =
            Pattern.compile("Date:\\s*(.+)");
    private static final Pattern DEBIT_CREDIT =
            Pattern.compile("account ending (?<acct>\\d{4}) has been "
                    + "(?<dir>debited|credited) with");
    private static final Pattern MERCHANT =
            Pattern.compile("Merchant / Remarks:\\s*(.+)");

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher head = DEBIT_CREDIT.matcher(body);
        if (!head.find()) return Optional.empty();

        BigDecimal amount = Amounts.first(body);
        if (amount == null) return Optional.empty();

        Matcher date = DATE.matcher(body);
        OffsetDateTime at = date.find() ? Dates.emailHeader(date.group(1)) : null;
        if (at == null) return Optional.empty();

        Direction dir = "debited".equals(head.group("dir"))
                ? Direction.DEBIT : Direction.CREDIT;

        Matcher mer = MERCHANT.matcher(body);
        String merchant = mer.find() ? mer.group(1).trim() : "";

        return Optional.of(new ParsedTxn(head.group("acct"), at, dir, amount,
                merchant, Amounts.statedBalance(body), m.messageId()));
    }
}
