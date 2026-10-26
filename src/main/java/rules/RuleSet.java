package rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

// Parsed YAML model for one client's rule file. fields is a LinkedHashMap because insertion
// order matters for handle hashing (handle.fields references declared field names in the
// order a rule author wrote them, and that order feeds HandleSynthesizer's join).
public class RuleSet {
    public String clientId;
    public boolean enabled = true;
    public MatchSpec match;
    public String bodyFormat = "plain_text";
    public MultiAlarmSpec multiAlarm;
    public LinkedHashMap<String, FieldSpec> fields = new LinkedHashMap<>();
    public HandleSpec handle;
    public String sourceFile;

    @SuppressWarnings("unchecked")
    public static RuleSet fromMap(Map<String, Object> raw, String sourceFile) {
        RuleSet rule = new RuleSet();
        rule.sourceFile = sourceFile;

        Object clientIdObj = raw.get("client_id");
        if (clientIdObj == null || clientIdObj.toString().isEmpty()) {
            // The one truly-fatal-to-this-file case: RuleLoader catches this and skips just
            // this file, logging the file name, rather than aborting the whole rules load.
            throw new IllegalArgumentException("Rule file " + sourceFile + " is missing required 'client_id'");
        }
        rule.clientId = clientIdObj.toString();

        Object enabledObj = raw.get("enabled");
        rule.enabled = enabledObj == null || Boolean.parseBoolean(enabledObj.toString());

        Object matchObj = raw.get("match");
        if (!(matchObj instanceof Map)) {
            throw new IllegalArgumentException("Rule file " + sourceFile + " is missing required 'match' block");
        }
        rule.match = MatchSpec.fromMap((Map<String, Object>) matchObj);

        Object bodyFormatObj = raw.get("body_format");
        rule.bodyFormat = bodyFormatObj == null ? "plain_text" : bodyFormatObj.toString();

        Object multiAlarmObj = raw.get("multi_alarm");
        if (multiAlarmObj instanceof Map) {
            rule.multiAlarm = MultiAlarmSpec.fromMap((Map<String, Object>) multiAlarmObj);
        }

        Object fieldsObj = raw.get("fields");
        if (fieldsObj instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) fieldsObj).entrySet()) {
                if (entry.getValue() instanceof Map) {
                    rule.fields.put(String.valueOf(entry.getKey()),
                            FieldSpec.fromMap((Map<String, Object>) entry.getValue()));
                }
            }
        }

        Object handleObj = raw.get("handle");
        if (handleObj instanceof Map) {
            rule.handle = HandleSpec.fromMap((Map<String, Object>) handleObj);
        }

        return rule;
    }

    public static class MatchSpec {
        public List<String> from = new ArrayList<>();
        public String subjectRegex;

        @SuppressWarnings("unchecked")
        static MatchSpec fromMap(Map<String, Object> raw) {
            MatchSpec spec = new MatchSpec();
            Object fromObj = raw.get("from");
            if (fromObj instanceof List) {
                for (Object o : (List<Object>) fromObj) {
                    spec.from.add(String.valueOf(o));
                }
            }
            Object subjectRegexObj = raw.get("subject_regex");
            spec.subjectRegex = subjectRegexObj == null ? null : subjectRegexObj.toString();
            return spec;
        }

        public boolean matches(String fromAddress, String subject) {
            if (!matchesFrom(fromAddress)) {
                return false;
            }
            if (subjectRegex != null && !subjectRegex.isEmpty()) {
                if (subject == null) {
                    return false;
                }
                return Pattern.compile(subjectRegex).matcher(subject).find();
            }
            return true;
        }

        public boolean matchesFrom(String fromAddress) {
            if (fromAddress == null) {
                return false;
            }
            String lowerFrom = fromAddress.toLowerCase();
            for (String pattern : from) {
                String lowerPattern = pattern.toLowerCase();
                if (lowerPattern.startsWith("*.")) {
                    // "*.domain" matches as a suffix against the address's domain part -
                    // keep the leading dot so "northcampus-bms.com" itself (no subdomain)
                    // does not spuriously match "*.northcampus-bms.com".
                    String domainSuffix = lowerPattern.substring(1);
                    if (lowerFrom.endsWith(domainSuffix)) {
                        return true;
                    }
                } else if (lowerFrom.equals(lowerPattern)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static class MultiAlarmSpec {
        public String blockSeparatorRegex;
        public int minBlocks = 1;

        static MultiAlarmSpec fromMap(Map<String, Object> raw) {
            MultiAlarmSpec spec = new MultiAlarmSpec();
            Object regexObj = raw.get("block_separator_regex");
            spec.blockSeparatorRegex = regexObj == null ? null : regexObj.toString();
            Object minBlocksObj = raw.get("min_blocks");
            spec.minBlocks = minBlocksObj == null ? 1 : Integer.parseInt(minBlocksObj.toString());
            return spec;
        }
    }

    public static class FieldSpec {
        public String literal;
        public String regex;
        // "body" | "subject" | "from" | "header:<Name>" - defaults to body.
        public String source = "body";
        public Map<String, String> valueMap;
        public String defaultValue;
        public List<String> dateFormatHints;
        public String timezone;

        @SuppressWarnings("unchecked")
        static FieldSpec fromMap(Map<String, Object> raw) {
            FieldSpec spec = new FieldSpec();
            Object literalObj = raw.get("literal");
            spec.literal = literalObj == null ? null : literalObj.toString();
            Object regexObj = raw.get("regex");
            spec.regex = regexObj == null ? null : regexObj.toString();
            Object sourceObj = raw.get("source");
            spec.source = sourceObj == null ? "body" : sourceObj.toString();
            Object defaultObj = raw.get("default");
            spec.defaultValue = defaultObj == null ? null : defaultObj.toString();

            Object valueMapObj = raw.get("value_map");
            if (valueMapObj instanceof Map) {
                spec.valueMap = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) valueMapObj).entrySet()) {
                    spec.valueMap.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }

            Object hintsObj = raw.get("date_format_hints");
            if (hintsObj instanceof List) {
                spec.dateFormatHints = new ArrayList<>();
                for (Object o : (List<Object>) hintsObj) {
                    spec.dateFormatHints.add(String.valueOf(o));
                }
            }

            Object timezoneObj = raw.get("timezone");
            spec.timezone = timezoneObj == null ? null : timezoneObj.toString();
            return spec;
        }
    }

    // handle.strategy only documents "synthesize" in the spec. When a rule omits the
    // handle: block entirely, RuleEngine falls back to reading a "handle" field declared
    // under fields: directly - the convention for clients with a stable natural point ID.
    public static class HandleSpec {
        public String strategy;
        public List<String> fields = new ArrayList<>();

        @SuppressWarnings("unchecked")
        static HandleSpec fromMap(Map<String, Object> raw) {
            HandleSpec spec = new HandleSpec();
            Object strategyObj = raw.get("strategy");
            spec.strategy = strategyObj == null ? null : strategyObj.toString();
            Object fieldsObj = raw.get("fields");
            if (fieldsObj instanceof List) {
                for (Object o : (List<Object>) fieldsObj) {
                    spec.fields.add(String.valueOf(o));
                }
            }
            return spec;
        }
    }
}
