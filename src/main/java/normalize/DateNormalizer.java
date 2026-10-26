package normalize;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

// Multi-format parse -> yyyy-MM-dd HH:mm:ss (UTC). Never throws: a parse failure is logged
// and falls back per-field, since a bad date in one email must not kill the whole poll cycle.
public class DateNormalizer {
    private static final Logger logger = Logger.getLogger(DateNormalizer.class.getName());

    private static final DateTimeFormatter OUTPUT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);

    private static final String[] BUILTIN_NAIVE_PATTERNS = {
            "MM/dd/yyyy HH:mm:ss",
            "dd-MM-yyyy HH:mm",
            "MMM d, yyyy h:mm:ss a"
    };

    // For the "timestamp" field: a record that fails to parse its stated time but clearly
    // just arrived is better logged as "now" than falling through to the target API's own
    // 1970-01-01 default.
    public static String normalizeTimestamp(String raw, List<String> hints, String ruleTimezone, String defaultTimezone) {
        ZoneId zone = resolveZone(ruleTimezone, defaultTimezone);
        ZonedDateTime parsed = tryParse(raw, hints, zone);
        if (parsed == null) {
            logger.warning("[DateNormalizer] Could not parse timestamp \"" + raw + "\", defaulting to now (UTC).");
            return ZonedDateTime.now(ZoneOffset.UTC).format(OUTPUT_FORMAT);
        }
        return parsed.withZoneSameInstant(ZoneOffset.UTC).format(OUTPUT_FORMAT);
    }

    // For "normaltime"/"offnormaltime": inventing a time that didn't happen is worse than
    // leaving it blank, so failure here defaults to empty, not "now".
    public static String normalizeOptional(String raw, List<String> hints, String ruleTimezone, String defaultTimezone) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        ZoneId zone = resolveZone(ruleTimezone, defaultTimezone);
        ZonedDateTime parsed = tryParse(raw, hints, zone);
        if (parsed == null) {
            logger.warning("[DateNormalizer] Could not parse optional date \"" + raw + "\", leaving blank.");
            return "";
        }
        return parsed.withZoneSameInstant(ZoneOffset.UTC).format(OUTPUT_FORMAT);
    }

    static ZoneId resolveZone(String ruleTimezone, String defaultTimezone) {
        String tz = (ruleTimezone != null && !ruleTimezone.isEmpty()) ? ruleTimezone : defaultTimezone;
        if (tz == null || tz.isEmpty()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            logger.warning("[DateNormalizer] Unknown timezone \"" + tz + "\", falling back to UTC.");
            return ZoneOffset.UTC;
        }
    }

    static ZonedDateTime tryParse(String raw, List<String> hints, ZoneId zone) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String trimmed = raw.trim();

        if (hints != null) {
            for (String hint : hints) {
                ZonedDateTime result = tryPatternNaive(trimmed, hint, zone);
                if (result != null) {
                    return result;
                }
            }
        }

        ZonedDateTime result = tryIso(trimmed);
        if (result != null) {
            return result;
        }

        result = tryRfc1123(trimmed);
        if (result != null) {
            return result;
        }

        for (String pattern : BUILTIN_NAIVE_PATTERNS) {
            result = tryPatternNaive(trimmed, pattern, zone);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    static ZonedDateTime tryIso(String raw) {
        try {
            return ZonedDateTime.parse(raw, DateTimeFormatter.ISO_DATE_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static ZonedDateTime tryRfc1123(String raw) {
        try {
            return ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static ZonedDateTime tryPatternNaive(String raw, String pattern, ZoneId zone) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
            LocalDateTime ldt = LocalDateTime.parse(raw, formatter);
            return ldt.atZone(zone);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return null;
        }
    }
}
