import deadletter.DeadLetterHandler;
import mail.MailboxPoller;
import mail.ProcessedStateStore;
import output.AlarmApiClient;
import rules.RuleEngine;
import rules.RuleLoader;
import rules.RuleSet;
import util.Utils;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

// Main entrypoint: poll scheduler loop wiring mailbox polling, rule matching, normalization,
// and the target-API client together.
public class MailAlarmBridge {
    private static final Logger logger = Logger.getLogger(MailAlarmBridge.class.getName());

    public static void main(String[] args) {
        System.setProperty("java.util.logging.SimpleFormatter.format", "[%1$tF %1$tT] [%4$-7s] %5$s %n");

        if (!Utils.loadConfiguration()) {
            logger.severe("[MailAlarmBridge] Failed to load config.properties (missing/blank required IMAP settings); exiting.");
            System.exit(1);
            return;
        }

        RuleLoader ruleLoader = new RuleLoader(Utils.getRulesDirectory());
        RuleEngine ruleEngine = new RuleEngine(Utils.getRulesDefaultTimezone());
        ProcessedStateStore stateStore = new ProcessedStateStore(Utils.getProcessedStorePath());
        MailboxPoller poller = new MailboxPoller(
                Utils.getImapHost(), Utils.getImapPort(), Utils.isImapSsl(),
                Utils.getImapUser(), Utils.getImapPassword(),
                Utils.getImapFolder(), Utils.getUnmatchedFolder(),
                Utils.isProcessedMoveEnabled(), Utils.getProcessedMoveFolder(),
                stateStore);
        AlarmApiClient apiClient = new AlarmApiClient(
                Utils.getApiUrl(), Utils.getApiConnectTimeoutMs(), Utils.getApiReadTimeoutMs(),
                Utils.getApiRetryMaxAttempts(), Utils.getApiRetryBackoffInitialMs(), Utils.getApiRetryBackoffMultiplier());
        DeadLetterHandler deadLetterHandler = new DeadLetterHandler(
                Utils.getAlertSmtpHost(), Utils.getAlertSmtpFrom(), Utils.getAlertSmtpTo(),
                Utils.getAlertUnmatchedThresholdCount(), Utils.getAlertUnmatchedThresholdWindowMinutes());

        logger.info("[MailAlarmBridge] Starting poll loop against " + Utils.getImapHost() + ":" + Utils.getImapPort()
                + " folder=" + Utils.getImapFolder() + ", target API=" + Utils.getApiUrl());

        runSchedulerLoop(ruleLoader, ruleEngine, poller, apiClient, deadLetterHandler);
    }

    static void runSchedulerLoop(RuleLoader ruleLoader, RuleEngine ruleEngine, MailboxPoller poller,
                                  AlarmApiClient apiClient, DeadLetterHandler deadLetterHandler) {
        long baseIntervalMs = Utils.getPollIntervalSeconds() * 1000L;
        long maxIntervalMs = Utils.getCircuitBreakerMaxIntervalSeconds() * 1000L;
        int failureThreshold = Utils.getCircuitBreakerFailureThreshold();

        int consecutiveFailures = 0;
        long currentIntervalMs = baseIntervalMs;
        boolean circuitOpenAlertSent = false;

        while (true) {
            List<RuleSet> rules = ruleLoader.maybeReload(Utils.isRulesReloadOnChange());
            MailboxPoller.PollResult result = poller.pollOnce(rules, ruleEngine, deadLetterHandler, apiClient, Utils.getPollBatchCap());

            if (result.cycleFullyFailed) {
                consecutiveFailures++;
                if (consecutiveFailures >= failureThreshold) {
                    // Extend the poll interval exponentially until a POST succeeds again -
                    // bounds API load/log noise during an extended target-API outage without
                    // losing anything (backlog just accumulates safely in the mailbox).
                    long extended = baseIntervalMs << Math.min(20, consecutiveFailures - failureThreshold + 1);
                    currentIntervalMs = Math.min(extended, maxIntervalMs);
                    if (!circuitOpenAlertSent) {
                        deadLetterHandler.sendAlert("MailAlarmBridge: circuit breaker open",
                                "Poll cycle has failed " + consecutiveFailures
                                        + " times in a row. Poll interval extended to " + (currentIntervalMs / 1000)
                                        + " seconds until a POST to the target API succeeds again.");
                        circuitOpenAlertSent = true;
                    }
                }
            } else {
                consecutiveFailures = 0;
                currentIntervalMs = baseIntervalMs;
                circuitOpenAlertSent = false;
            }

            logger.log(result.cycleFullyFailed ? Level.WARNING : Level.INFO,
                    "[MailAlarmBridge] Poll cycle: fetched=" + result.emailsFetched
                            + " processed=" + result.emailsProcessed + " failed=" + result.emailsFailed
                            + " deadLettered=" + result.emailsDeadLettered + " cycleFullyFailed=" + result.cycleFullyFailed);

            sleepQuietly(currentIntervalMs);
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
