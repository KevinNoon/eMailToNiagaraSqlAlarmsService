package util;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Logger;

// config.properties loader, mirroring NiagaraSQLAlarmServer's Utils.java pattern: Properties
// via try-with-resources, private static cache fields, one-line getters.
public class Utils {
    private static final Logger logger = Logger.getLogger(Utils.class.getName());

    private static String imapHost;
    private static int imapPort;
    private static boolean imapSsl;
    private static String imapUser;
    private static String imapPassword;
    private static String imapFolder;
    private static int pollIntervalSeconds;
    private static String processedStrategy;
    private static String processedStorePath;
    private static String unmatchedFolder;
    private static boolean processedMoveEnabled;
    private static String processedMoveFolder;
    private static int pollBatchCap;

    private static String rulesDirectory;
    private static boolean rulesReloadOnChange;
    private static String rulesDefaultTimezone;

    private static String apiUrl;
    private static int apiConnectTimeoutMs;
    private static int apiReadTimeoutMs;
    private static int apiRetryMaxAttempts;
    private static long apiRetryBackoffInitialMs;
    private static double apiRetryBackoffMultiplier;
    private static int circuitBreakerFailureThreshold;
    private static int circuitBreakerMaxIntervalSeconds;

    private static String alertSmtpHost;
    private static String alertSmtpFrom;
    private static String alertSmtpTo;
    private static int alertUnmatchedThresholdCount;
    private static int alertUnmatchedThresholdWindowMinutes;

    public static boolean loadConfiguration() {
        return loadConfiguration("config.properties");
    }

    static boolean loadConfiguration(String configPath) {
        Properties prop = new Properties();
        try (InputStream input = new FileInputStream(configPath)) {
            prop.load(input);

            imapHost = prop.getProperty("mail.imap.host", "");
            imapUser = prop.getProperty("mail.imap.user", "");
            imapPassword = prop.getProperty("mail.imap.password", "");
            if (imapHost.isEmpty() || imapUser.isEmpty() || imapPassword.isEmpty()) {
                logger.warning("[Utils] mail.imap.host/user/password are required and must not be blank.");
                return false;
            }

            imapPort = Integer.parseInt(prop.getProperty("mail.imap.port", "993"));
            imapSsl = Boolean.parseBoolean(prop.getProperty("mail.imap.ssl", "true"));
            imapFolder = prop.getProperty("mail.imap.folder", "INBOX");
            pollIntervalSeconds = Integer.parseInt(prop.getProperty("mail.poll.interval.seconds", "60"));
            processedStrategy = prop.getProperty("mail.processed.strategy", "FLAG_AND_LOCAL");
            processedStorePath = prop.getProperty("mail.processed.store.path", "./data/processed-ids.log");
            unmatchedFolder = prop.getProperty("mail.unmatched.folder", "Unmatched");
            processedMoveEnabled = Boolean.parseBoolean(prop.getProperty("mail.processed.move.enabled", "false"));
            processedMoveFolder = prop.getProperty("mail.processed.move.folder", "Processed");
            pollBatchCap = Integer.parseInt(prop.getProperty("mail.poll.batch.cap", "50"));

            rulesDirectory = prop.getProperty("rules.directory", "./rules");
            rulesReloadOnChange = Boolean.parseBoolean(prop.getProperty("rules.reload.on.change", "true"));
            rulesDefaultTimezone = prop.getProperty("rules.default.timezone", "UTC");

            apiUrl = prop.getProperty("api.url", "http://localhost:5555/api/endpoint");
            apiConnectTimeoutMs = Integer.parseInt(prop.getProperty("api.connect.timeout.ms", "5000"));
            apiReadTimeoutMs = Integer.parseInt(prop.getProperty("api.read.timeout.ms", "10000"));
            apiRetryMaxAttempts = Integer.parseInt(prop.getProperty("api.retry.max.attempts", "5"));
            apiRetryBackoffInitialMs = Long.parseLong(prop.getProperty("api.retry.backoff.initial.ms", "2000"));
            apiRetryBackoffMultiplier = Double.parseDouble(prop.getProperty("api.retry.backoff.multiplier", "2.0"));
            circuitBreakerFailureThreshold = Integer.parseInt(prop.getProperty("api.circuitbreaker.failure.threshold", "5"));
            circuitBreakerMaxIntervalSeconds = Integer.parseInt(prop.getProperty("api.circuitbreaker.max.interval.seconds", "1800"));

            alertSmtpHost = prop.getProperty("alert.smtp.host", "");
            alertSmtpFrom = prop.getProperty("alert.smtp.from", "");
            alertSmtpTo = prop.getProperty("alert.smtp.to", "");
            alertUnmatchedThresholdCount = Integer.parseInt(prop.getProperty("alert.unmatched.threshold.count", "3"));
            alertUnmatchedThresholdWindowMinutes = Integer.parseInt(prop.getProperty("alert.unmatched.threshold.window.minutes", "15"));

            return true;
        } catch (IOException ex) {
            logger.warning("[Utils] Error reading config file: " + ex.getMessage());
            return false;
        }
    }

    public static String getImapHost() { return imapHost; }
    public static int getImapPort() { return imapPort; }
    public static boolean isImapSsl() { return imapSsl; }
    public static String getImapUser() { return imapUser; }
    public static String getImapPassword() { return imapPassword; }
    public static String getImapFolder() { return imapFolder; }
    public static int getPollIntervalSeconds() { return pollIntervalSeconds; }
    public static String getProcessedStrategy() { return processedStrategy; }
    public static String getProcessedStorePath() { return processedStorePath; }
    public static String getUnmatchedFolder() { return unmatchedFolder; }
    public static boolean isProcessedMoveEnabled() { return processedMoveEnabled; }
    public static String getProcessedMoveFolder() { return processedMoveFolder; }
    public static int getPollBatchCap() { return pollBatchCap; }

    public static String getRulesDirectory() { return rulesDirectory; }
    public static boolean isRulesReloadOnChange() { return rulesReloadOnChange; }
    public static String getRulesDefaultTimezone() { return rulesDefaultTimezone; }

    public static String getApiUrl() { return apiUrl; }
    public static int getApiConnectTimeoutMs() { return apiConnectTimeoutMs; }
    public static int getApiReadTimeoutMs() { return apiReadTimeoutMs; }
    public static int getApiRetryMaxAttempts() { return apiRetryMaxAttempts; }
    public static long getApiRetryBackoffInitialMs() { return apiRetryBackoffInitialMs; }
    public static double getApiRetryBackoffMultiplier() { return apiRetryBackoffMultiplier; }
    public static int getCircuitBreakerFailureThreshold() { return circuitBreakerFailureThreshold; }
    public static int getCircuitBreakerMaxIntervalSeconds() { return circuitBreakerMaxIntervalSeconds; }

    public static String getAlertSmtpHost() { return alertSmtpHost; }
    public static String getAlertSmtpFrom() { return alertSmtpFrom; }
    public static String getAlertSmtpTo() { return alertSmtpTo; }
    public static int getAlertUnmatchedThresholdCount() { return alertUnmatchedThresholdCount; }
    public static int getAlertUnmatchedThresholdWindowMinutes() { return alertUnmatchedThresholdWindowMinutes; }
}
