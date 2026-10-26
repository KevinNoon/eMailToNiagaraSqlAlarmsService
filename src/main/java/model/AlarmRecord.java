package model;

// Normalized field set matching NiagaraSQLAlarmServer's /api/endpoint contract exactly.
// sourceMessageId/clientId are bridge-internal bookkeeping, never serialized to the API.
public class AlarmRecord {
    public String estate;
    public String handle;
    public String siteName;
    public String pointName;
    public String pointValue;
    public String alarmClass;
    public String alarmState;
    public String message;
    public String timestamp;
    public String normalTime;
    public String offNormalTime;

    public String sourceMessageId;
    public String clientId;

    public String toJsonObject() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        appendField(sb, "estate", estate, true);
        appendField(sb, "Handle", handle, false);
        appendField(sb, "site_name", siteName, false);
        appendField(sb, "point_name", pointName, false);
        appendField(sb, "point_value", pointValue, false);
        appendField(sb, "alarm_class", alarmClass, false);
        appendField(sb, "alarm_state", alarmState, false);
        appendField(sb, "Message", message, false);
        appendField(sb, "TimeStamp", timestamp, false);
        // NormalTime/OffNormalTime are omitted entirely when blank rather than sent as "" -
        // a record that never had a normal/offnormal time shouldn't imply the target API's
        // own 1970-01-01 fallback was intentionally requested.
        if (normalTime != null && !normalTime.isEmpty()) {
            appendField(sb, "NormalTime", normalTime, false);
        }
        if (offNormalTime != null && !offNormalTime.isEmpty()) {
            appendField(sb, "OffNormalTime", offNormalTime, false);
        }
        sb.append('}');
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String key, String value, boolean first) {
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(key).append("\":\"").append(escapeJsonString(value)).append('"');
    }

    static String escapeJsonString(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' || c == '"') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
