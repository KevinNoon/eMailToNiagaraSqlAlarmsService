package rules;

import model.AlarmRecord;
import normalize.DateNormalizer;
import normalize.HandleSynthesizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

// The core novel component: matches an email to a RuleSet, then applies field extraction -
// including multi-alarm block splitting - to produce the normalized AlarmRecord list.
public class RuleEngine {
    private static final Logger logger = Logger.getLogger(RuleEngine.class.getName());

    private final String defaultTimezone;

    public RuleEngine(String defaultTimezone) {
        this.defaultTimezone = defaultTimezone;
    }

    public static RuleSet match(List<RuleSet> rules, String fromAddress, String subject) {
        for (RuleSet rule : rules) {
            if (!rule.enabled) {
                continue;
            }
            if (rule.match.matches(fromAddress, subject)) {
                return rule;
            }
        }
        return null;
    }

    public List<AlarmRecord> extract(RuleSet rule, String subject, String body, String fromAddress,
                                      Map<String, String> headers, String messageId) {
        List<String> blocks;
        if (rule.multiAlarm == null) {
            String text = body == null ? "" : body;
            blocks = text.trim().isEmpty() ? List.of() : List.of(text);
        } else {
            blocks = FieldExtractor.splitBlocks(body, rule.multiAlarm.blockSeparatorRegex);
            if (blocks.size() < rule.multiAlarm.minBlocks) {
                // A stale rule (client changed its format) should surface as unmatched, not
                // vanish as a silently-empty success.
                logger.warning("[RuleEngine] client=" + rule.clientId + " multi_alarm split produced "
                        + blocks.size() + " block(s), below min_blocks=" + rule.multiAlarm.minBlocks
                        + "; treating email as unmatched.");
                return List.of();
            }
        }

        List<AlarmRecord> records = new ArrayList<>();
        for (String block : blocks) {
            records.add(extractOneRecord(rule, block, subject, fromAddress, headers, messageId));
        }
        return records;
    }

    public AlarmRecord extractOneRecord(RuleSet rule, String blockText, String subject, String fromAddress,
                                         Map<String, String> headers, String messageId) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (Map.Entry<String, RuleSet.FieldSpec> entry : rule.fields.entrySet()) {
            raw.put(entry.getKey(),
                    FieldExtractor.extract(entry.getValue(), blockText, subject, fromAddress, headers, rule.clientId));
        }

        AlarmRecord record = new AlarmRecord();
        record.clientId = rule.clientId;
        record.sourceMessageId = messageId;
        record.estate = raw.getOrDefault("estate", "");
        record.siteName = raw.getOrDefault("site_name", "");
        record.pointName = raw.getOrDefault("point_name", "");
        record.pointValue = raw.getOrDefault("point_value", "");
        record.alarmClass = raw.getOrDefault("alarm_class", "");
        record.alarmState = raw.getOrDefault("alarm_state", "");
        record.message = raw.getOrDefault("message", "");

        RuleSet.FieldSpec timestampSpec = rule.fields.get("timestamp");
        record.timestamp = DateNormalizer.normalizeTimestamp(raw.get("timestamp"),
                timestampSpec == null ? null : timestampSpec.dateFormatHints,
                timestampSpec == null ? null : timestampSpec.timezone,
                defaultTimezone);

        RuleSet.FieldSpec normalSpec = rule.fields.get("normaltime");
        record.normalTime = DateNormalizer.normalizeOptional(raw.get("normaltime"),
                normalSpec == null ? null : normalSpec.dateFormatHints,
                normalSpec == null ? null : normalSpec.timezone,
                defaultTimezone);

        RuleSet.FieldSpec offNormalSpec = rule.fields.get("offnormaltime");
        record.offNormalTime = DateNormalizer.normalizeOptional(raw.get("offnormaltime"),
                offNormalSpec == null ? null : offNormalSpec.dateFormatHints,
                offNormalSpec == null ? null : offNormalSpec.timezone,
                defaultTimezone);

        record.handle = resolveHandle(rule, raw);
        return record;
    }

    private String resolveHandle(RuleSet rule, Map<String, String> raw) {
        if (rule.handle != null && "synthesize".equalsIgnoreCase(rule.handle.strategy)) {
            List<String> values = new ArrayList<>();
            for (String fieldName : rule.handle.fields) {
                values.add(raw.get(fieldName));
            }
            return HandleSynthesizer.synthesize(rule.clientId, values);
        }

        String directHandle = raw.get("handle");
        if (directHandle != null && !directHandle.isEmpty()) {
            return directHandle;
        }

        // Handle must never be blank - it's the target API's dedupe key. Falling back to
        // hashing every extracted field is a last-resort safety net, not a real strategy.
        logger.warning("[RuleEngine] client=" + rule.clientId
                + " has no handle.strategy and no 'handle' field; synthesizing from all extracted fields.");
        return HandleSynthesizer.synthesize(rule.clientId, new ArrayList<>(raw.values()));
    }
}
