package in.simplifymoney.ledgersync.parse;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Bank SMS carry a local date and time and no timezone. The customer, the bank
 * and the branch are all in India, so these are IST.
 */
public final class Dates {

    private Dates() {}

    public static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

    private static final List<DateTimeFormatter> SMS_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd-MM-yy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.ENGLISH));

    /** The RFC-style timestamp a bank alert email carries in its "Date:" header. */
    private static final DateTimeFormatter EMAIL_HEADER =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    /** Parse a local date-time written by a bank, as IST. */
    public static OffsetDateTime ist(String dateAndTime) {
        for (DateTimeFormatter f : SMS_FORMATS) {
            try {
                return LocalDateTime.parse(dateAndTime.trim(), f).atOffset(IST);
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        return null;
    }

    /**
     * Parse the "Date:" header of a bank email, keeping the offset it carries
     * (e.g. "Wed, 01 Jul 2026 09:02:00 +0530"). This is when the transaction
     * happened, which is what the matching SMS also reports.
     */
    public static OffsetDateTime emailHeader(String dateHeader) {
        try {
            return OffsetDateTime.parse(dateHeader.trim(), EMAIL_HEADER);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
