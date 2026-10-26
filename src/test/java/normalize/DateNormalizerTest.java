package normalize;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateNormalizerTest {

    @Test
    void hintPatternTakesPriorityOverBuiltins() {
        // Without a hint this would try MM/dd/yyyy first; the hint forces dd/MM/yyyy reading.
        String result = DateNormalizer.normalizeTimestamp(
                "25/12/2026 10:00:00", List.of("dd/MM/yyyy HH:mm:ss"), "UTC", "UTC");
        assertEquals("2026-12-25 10:00:00", result);
    }

    @Test
    void isoWithOffsetUsesOwnOffsetNotRuleTimezone() {
        String result = DateNormalizer.normalizeTimestamp(
                "2026-07-29T08:30:16-05:00", null, "America/Chicago", "UTC");
        assertEquals("2026-07-29 13:30:16", result);
    }

    @Test
    void isoWithoutOffsetFallsThroughToNaivePatternsOrRuleZone() {
        // Pure ISO local date-time without zone/offset is not matched by ISO_DATE_TIME
        // (which requires a zone), so it falls through to naive built-ins; none of the three
        // built-in patterns match "2026-07-29T08:30:16" so this exercises the "now" fallback.
        String result = DateNormalizer.normalizeTimestamp(
                "2026-07-29T08:30:16", null, "UTC", "UTC");
        assertTrue(result.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    void rfc1123EmailHeaderFormat() {
        String result = DateNormalizer.normalizeTimestamp(
                "Wed, 29 Jul 2026 08:30:16 -0500", null, "UTC", "UTC");
        assertEquals("2026-07-29 13:30:16", result);
    }

    @Test
    void builtinSlashPatternWithExplicitRuleTimezone() {
        String result = DateNormalizer.normalizeTimestamp(
                "07/29/2026 14:05:00", null, "America/Chicago", "UTC");
        assertEquals("2026-07-29 19:05:00", result);
    }

    @Test
    void builtinDashPatternWithExplicitRuleTimezone() {
        String result = DateNormalizer.normalizeTimestamp(
                "29-07-2026 14:05", null, "America/Chicago", "UTC");
        assertEquals("2026-07-29 19:05:00", result);
    }

    @Test
    void builtinMonthNamePatternWithExplicitRuleTimezone() {
        String result = DateNormalizer.normalizeTimestamp(
                "Jul 29, 2026 2:05:00 PM", null, "America/Chicago", "UTC");
        assertEquals("2026-07-29 19:05:00", result);
    }

    @Test
    void ruleOmittedTimezoneFallsBackToDefaultTimezone() {
        String result = DateNormalizer.normalizeTimestamp(
                "07/29/2026 14:05:00", null, "", "America/Chicago");
        assertEquals("2026-07-29 19:05:00", result);
    }

    @Test
    void dstSpringForwardGapStillProducesValidOutputNotException() {
        // 2026-03-08 02:30 America/Chicago falls in the spring-forward gap (clocks jump
        // 02:00 -> 03:00). LocalDateTime.atZone resolves this via the zone's transition
        // rules rather than throwing - assert we get *some* valid UTC output.
        String result = assertDoesNotThrow(() -> DateNormalizer.normalizeTimestamp(
                "03/08/2026 02:30:00", null, "America/Chicago", "UTC"));
        assertTrue(result.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    void dstFallBackAmbiguousHourStillProducesValidOutputNotException() {
        // 2026-11-01 01:30 America/Chicago is ambiguous (occurs twice). Assert no exception
        // and a valid UTC output is produced either way.
        String result = assertDoesNotThrow(() -> DateNormalizer.normalizeTimestamp(
                "11/01/2026 01:30:00", null, "America/Chicago", "UTC"));
        assertTrue(result.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    void garbageTimestampDefaultsToNowUtcNeverThrows() {
        String result = assertDoesNotThrow(() -> DateNormalizer.normalizeTimestamp(
                "not a date at all", null, "UTC", "UTC"));
        assertTrue(result.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    void garbageOptionalDateDefaultsToBlankNeverThrows() {
        String result = assertDoesNotThrow(() -> DateNormalizer.normalizeOptional(
                "not a date at all", null, "UTC", "UTC"));
        assertEquals("", result);
    }

    @Test
    void blankOptionalDateStaysBlank() {
        assertEquals("", DateNormalizer.normalizeOptional("", null, "UTC", "UTC"));
        assertEquals("", DateNormalizer.normalizeOptional(null, null, "UTC", "UTC"));
    }
}
