package rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

// Regex extraction helpers behind RuleEngine - package-private, meant to be unit-tested
// directly, matching the existing codebase's "hand-rolled regex extractor" philosophy.
class FieldExtractor {
    private static final Logger logger = Logger.getLogger(FieldExtractor.class.getName());

    static String extract(RuleSet.FieldSpec spec, String body, String subject, String from,
                           Map<String, String> headers, String clientIdForLogging) {
        if (spec == null) {
            return "";
        }
        if (spec.literal != null) {
            return spec.literal;
        }
        if (spec.regex == null) {
            return spec.defaultValue == null ? "" : spec.defaultValue;
        }

        String source = resolveSource(spec.source, body, subject, from, headers);
        if (source == null) {
            source = "";
        }

        try {
            Pattern pattern = Pattern.compile(spec.regex, Pattern.MULTILINE);
            Matcher matcher = pattern.matcher(source);
            if (!matcher.find()) {
                return spec.defaultValue == null ? "" : spec.defaultValue;
            }

            String rawVal;
            try {
                rawVal = matcher.group("val");
            } catch (IllegalArgumentException e) {
                logger.warning("[FieldExtractor] client=" + clientIdForLogging + " regex \"" + spec.regex
                        + "\" has no named group 'val'; using default.");
                return spec.defaultValue == null ? "" : spec.defaultValue;
            }

            String trimmedVal = rawVal == null ? null : rawVal.trim();
            if (spec.valueMap != null && !spec.valueMap.isEmpty()) {
                String mapped = applyValueMap(trimmedVal, spec.valueMap);
                // An unmapped raw value (e.g. an unexpected "Status: UNKNOWN") falls back to
                // default rather than passing an unrecognized state straight through.
                return mapped != null ? mapped : (spec.defaultValue == null ? "" : spec.defaultValue);
            }
            return trimmedVal != null ? trimmedVal : (spec.defaultValue == null ? "" : spec.defaultValue);
        } catch (PatternSyntaxException e) {
            logger.warning("[FieldExtractor] client=" + clientIdForLogging + " invalid regex \"" + spec.regex
                    + "\": " + e.getMessage() + "; using default.");
            return spec.defaultValue == null ? "" : spec.defaultValue;
        }
    }

    // Splits body into per-record blocks using a zero-width-lookahead separator regex.
    // Content before the first match is preamble (e.g. a shared header line above the first
    // "Point:" line) and is intentionally dropped, not returned as a spurious extra block.
    static List<String> splitBlocks(String body, String blockSeparatorRegex) {
        String text = body == null ? "" : body;
        if (blockSeparatorRegex == null || blockSeparatorRegex.isEmpty()) {
            return text.trim().isEmpty() ? List.of() : List.of(text);
        }

        Pattern pattern = Pattern.compile(blockSeparatorRegex, Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(text);
        List<Integer> starts = new ArrayList<>();
        int searchFrom = 0;
        while (searchFrom <= text.length() && matcher.find(searchFrom)) {
            int matchStart = matcher.start();
            starts.add(matchStart);
            // Zero-width lookaheads don't consume input; advance by at least one character
            // so the next find() call makes progress instead of matching the same spot forever.
            searchFrom = Math.max(matchStart + 1, matcher.end());
        }

        if (starts.isEmpty()) {
            return List.of();
        }

        List<String> blocks = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = (i + 1 < starts.size()) ? starts.get(i + 1) : text.length();
            String block = text.substring(from, to);
            if (!block.trim().isEmpty()) {
                blocks.add(block);
            }
        }
        return blocks;
    }

    // Returns the mapped value, or null if rawVal didn't match any key (case-insensitive) -
    // callers decide whether "unmapped" means "use default" or "pass through unchanged".
    static String applyValueMap(String rawVal, Map<String, String> valueMap) {
        if (valueMap == null || valueMap.isEmpty() || rawVal == null) {
            return rawVal;
        }
        for (Map.Entry<String, String> entry : valueMap.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(rawVal)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String resolveSource(String source, String body, String subject, String from,
                                          Map<String, String> headers) {
        if (source == null || source.isEmpty() || "body".equalsIgnoreCase(source)) {
            return body;
        }
        if ("subject".equalsIgnoreCase(source)) {
            return subject;
        }
        if ("from".equalsIgnoreCase(source)) {
            return from;
        }
        if (source.toLowerCase().startsWith("header:") && headers != null) {
            String headerName = source.substring("header:".length());
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(headerName)) {
                    return entry.getValue();
                }
            }
            return null;
        }
        return body;
    }
}
