# Spec: MailAlarmBridge — Email-to-Alarm-API Bridge Service

## Context

`NiagaraSQLAlarmServer` currently receives alarm data from JACE/Supervisor hardware over a direct HTTP POST to `/api/endpoint`. Some clients, however, will deliver alarm notifications by **email** instead, in whatever format their own BMS/alarm system happens to produce — a format this project has no control over and cannot standardize. We need a separate, standalone application that polls a mailbox, extracts alarm data from arbitrary per-client email formats, and forwards it to the existing `/api/endpoint` API so these clients land in the same MySQL/Influx pipeline as JACE-sourced alarms.

This document specifies that new application, called **MailAlarmBridge**, as a new standalone repository (not a module of `NiagaraSQLAlarmServer`), in Java/Maven to match this project's toolchain and conventions.

## Target API Contract (reference — already live, do not change)

`POST http://<host>:5555/api/endpoint` (`NiagaraSQLAlarmServer.java`), plain HTTP, **no authentication**. Accepts a single JSON object or an array (batch). Key facts the bridge must design around:

- Only `estate` is required; empty/missing `estate` on a record silently drops that record (400 only if *every* record in the request lacks it).
- `handle` is the **dedupe key** — MySQL `UNIQUE KEY uq_handle (Handle)`, upserted via `ON DUPLICATE KEY UPDATE`. Same handle = same row updated, not duplicated.
- `estate` → MySQL schema via `sanitizeDbName` (lowercase, non `[a-zA-Z0-9_]` → `_`). **Schemas/tables are not auto-created** — must pre-exist.
- `timestamp`/`TimeStamp`, `normaltime`/`NormalTime`, `offnormaltime`/`OffNormalTime` must be **exactly** `yyyy-MM-dd HH:mm:ss` or they silently become `1970-01-01 00:00:00` with no error. The bridge owns 100% of the responsibility for correct formatting — the target API will never complain.
- Responses: `200 {"status":"success","processed":n}`, `400` (zero records had estate), `500` (any failure — no per-record detail in a batch).
- Example: `{"estate":"North Campus","Handle":"H1","site_name":"Site A","point_name":"AHU-1","point_value":"72.5","alarm_class":"Critical","alarm_state":"offnormal","Message":"Test alarm","TimeStamp":"2026-07-29 08:30:16"}`

## Confirmed Decisions

- **Email source:** IMAP/IMAPS polling (not Graph API, not a watched folder). Poll interval, not IDLE, for v1.
- **Parsing strategy:** Per-client configurable rules (YAML), not an LLM and not a single hardcoded parser — the client's format is out of our control and varies per sender.
- **New standalone repo**, Java 17 + Maven, matching `NiagaraSQLAlarmServer`'s toolchain (`java.util.logging` with the same formatter, flat `config.properties`, `maven-assembly-plugin` jar-with-dependencies).
- **Timezone:** normalize all email-sourced timestamps to **UTC**. Simpler, avoids DST ambiguity, doesn't require tracking a timezone per estate. Accept that these rows may look different from existing JACE-sourced local-time rows unless/until that's reconciled separately — not this app's problem to solve.
- **Unmatched-sender emails:** never silently dropped. Flag/quarantine + log + **email alert via SMTP** if unmatched volume crosses a threshold in a time window.
- **"Return to normal" correlation:** treated as a per-client reality, not an engineering problem to solve universally. Some clients will only ever send one-shot offnormal notifications with no clearable follow-up — document this per rule file, don't block onboarding on it.

## New Dependencies (beyond the existing app's MySQL-connector/JUnit footprint)

- `org.eclipse.angus:angus-mail` — Jakarta Mail (IMAP, MIME multipart/HTML parsing, flag manipulation). Required; hand-rolling IMAP is not worth it.
- `org.yaml:snakeyaml` — parses per-client rule files. The one deliberate added-dependency decision; rule files need nested/list structure flat properties can't express well.
- Outbound JSON to `/api/endpoint` is built natively (string concatenation, same philosophy as the existing app's regex-based inbound extraction) — no JSON library needed.

## Repo Layout

```
MailAlarmBridge/
  pom.xml
  config.properties.example
  rules/                          <- one YAML file per client/sender
    north-campus.yaml
  src/main/java/
    MailAlarmBridge.java          <- main entrypoint, poll scheduler loop
    Utils.java                    <- config.properties loader (mirrors existing Utils.java)
    mail/
      MailboxPoller.java          <- IMAP connect/fetch/flag logic
      ProcessedStateStore.java    <- local Message-ID dedupe safety net
    rules/
      RuleSet.java                <- parsed YAML model for one client
      RuleEngine.java             <- matches an email to a RuleSet, applies extraction
      RuleLoader.java             <- loads/hot-reloads the rules/ directory
      FieldExtractor.java         <- regex extraction helpers
    model/
      AlarmRecord.java            <- normalized target-API field set
    normalize/
      DateNormalizer.java         <- multi-format parse -> yyyy-MM-dd HH:mm:ss (UTC)
      HandleSynthesizer.java      <- deterministic handle hashing
    output/
      AlarmApiClient.java         <- batches + POSTs to NiagaraSQLAlarmServer, retry/backoff
    deadletter/
      DeadLetterHandler.java      <- unmatched/failed email handling + SMTP alerting
  src/test/java/
    rules/RuleEngineTest.java
    normalize/DateNormalizerTest.java
    normalize/HandleSynthesizerTest.java
    fixtures/<client-id>/*.eml    <- saved sample emails per client
```

## Data Flow

```
Mailbox (IMAP) --poll--> MailboxPoller --> RuleEngine.match(sender/subject)
                                              |matched              |no match
                                              v                      v
                                      FieldExtractor          DeadLetterHandler
                                      (split multi-alarm         (flag/quarantine,
                                       blocks, extract raw        log, SMTP alert on
                                       fields)                     threshold)
                                              v
                                      Normalize per record
                                      (DateNormalizer -> UTC,
                                       HandleSynthesizer if
                                       no natural ID)
                                              v
                                      List<AlarmRecord> (1 email -> N records)
                                              v
                                      AlarmApiClient: batch -> POST /api/endpoint
                                              |success                |failure
                                              v                       v
                                    mark email processed      leave unprocessed;
                                    (flag + local store)      retry next poll
                                                               (capped + circuit
                                                                breaker, see below)
```

**Key invariant:** an email is marked processed only after a confirmed `200` for the batch containing its records. Failures leave the email untouched so the next poll retries — safe because the target API's handle-based upsert makes resending idempotent. No separate retry queue needed; the mailbox itself is the retry queue.

## Rule-Definition Format (per-client YAML)

One file per client in `rules/`, matched in file order (first match wins). Regex-with-named-groups, not a DOM/XPath engine — matches the existing codebase's own "hand-rolled regex extractor" philosophy (`extractJsonValue` in `NiagaraSQLAlarmServer.java`) and keeps rules debuggable by an operator without a new query language. If a client's HTML is too irregular for regex, a per-rule `body_format: html_dom` + CSS selector is an acceptable escape hatch, not the default.

```yaml
client_id: north-campus
enabled: true

match:
  from: ["alerts@northcampus-bms.com", "*.northcampus-bms.com"]
  subject_regex: "^BMS ALARM NOTIFICATION"   # optional, ANDed with from

body_format: plain_text                      # plain_text | html | auto

multi_alarm:                                 # omit entirely for single-alarm emails
  block_separator_regex: "(?=^Point:\\s)"
  min_blocks: 1                              # 0 matched blocks -> dead-letter, not silently empty

fields:
  estate:
    literal: "North Campus"                 # or regex+source if the email states it
  point_name:
    regex: "Point:\\s*(?<val>.+)"
    source: body
  point_value:
    regex: "Value:\\s*(?<val>.+)"
    source: body
    default: ""
  alarm_state:
    regex: "Status:\\s*(?<val>\\w+)"
    source: body
    value_map: {ALARM: "offnormal", CLEAR: "normal"}
    default: "offnormal"
  message:
    regex: "Description:\\s*(?<val>.+)"
    source: body
    default: ""
  timestamp:
    regex: "Time:\\s*(?<val>.+)"
    source: body
    date_format_hints: ["MM/dd/yyyy HH:mm:ss"]
    timezone: "America/Chicago"              # naive local time -> converted to UTC

handle:
  strategy: synthesize                       # this client has no stable per-point ID
  fields: ["estate", "point_name"]           # NOT point_value/message - those vary per occurrence
```

**Worked example (plain text, single alarm, synthesized handle):**

Email body:
```
BMS ALARM NOTIFICATION
Point: AHU-3 Supply Temp
Value: 91.2 F
Status: ALARM
Description: High supply air temperature
Time: 07/29/2026 14:05:00
```
→ produces one `AlarmRecord`:
```json
{"estate":"North Campus","Handle":"north-campus:3f1a9c2e8b77...","point_name":"AHU-3 Supply Temp","point_value":"91.2 F","alarm_state":"offnormal","Message":"High supply air temperature","TimeStamp":"2026-07-29 19:05:00"}
```
(14:05 `America/Chicago` → 19:05 UTC per the timezone decision above.)

**Multi-alarm/multi-estate HTML digest** works the same way: `block_separator_regex` splits the table into per-row blocks before `fields` extraction runs on each, and a single client email can legitimately produce records for multiple different estates in one batch POST (the target API accepts mixed-estate arrays natively).

## Handle Synthesis

```
handle = client_id + ":" + sha256(lower(join("|", declared field values)))[:24]
```
Deterministic and namespaced per client to avoid cross-client collisions. This guarantees the *same point* always synthesizes the *same handle* (so repeated/resent offnormal emails upsert instead of duplicating), but it does **not** manufacture a "return to normal" event that the client's email stream never sends — see the per-client reality documented in Confirmed Decisions above. When onboarding a client, explicitly note in its rule file (as a comment) whether a normal/cleared follow-up email exists at all; don't engineer around its absence.

## Date/Time Normalization (`DateNormalizer`)

1. Try the rule's `date_format_hints` first.
2. Fall back to a built-in chain: ISO-8601 (with/without offset/`T`), RFC 1123 (email `Date:` header format), `MM/dd/yyyy HH:mm:ss`, `dd-MM-yyyy HH:mm`, `MMM d, yyyy h:mm:ss a`.
3. If the parsed value has explicit offset/zone info, use it directly; otherwise apply the rule's `timezone` (falls back to `rules.default.timezone` config if the rule omits it).
4. Always emit the final value converted to **UTC**, formatted `yyyy-MM-dd HH:mm:ss`.
5. Never let a parse failure throw and kill the batch: on failure, log a WARNING with the raw string + client_id. For `timestamp`, default to "now" (UTC) rather than inventing nothing — a record that fails to parse its stated time but clearly just arrived is better logged as "now" than falling through to the target API's own `1970-01-01` default. For `normaltime`/`offnormaltime`, default to omitted/empty — inventing a normal-time that didn't happen is worse than leaving it blank.

## Processed-State Tracking

Two-layer, so human mailbox activity (opening/marking-read) can't cause silent data loss:

1. **Primary:** custom IMAP keyword flag `$MailAlarmBridge-Processed`, set only after a confirmed `200`. Poll query is `SEARCH NOT KEYWORD $MailAlarmBridge-Processed`, not `UNSEEN`. Also set `\Seen` for mailbox-UI friendliness, but never rely on it for logic.
2. **Secondary safety net:** local append-only file of processed `Message-ID` values (RFC 5322, stable across UID/UIDVALIDITY resets and folder moves) — check before processing, append after success. Keeps the flat-file philosophy of the existing project rather than pulling in an embedded DB for this.

Moving processed emails to a separate folder is available as an **optional** config switch (operators who want visual confirmation) but not the default — it mutates a mailbox the bridge doesn't own and can conflict with the client's own mail rules.

## Unmatched-Sender / Dead-Letter Handling

1. No matching rule → flag with a custom keyword (`$MailAlarmBridge-Unmatched`) or move to a configurable `Unmatched` folder; never silently drop or fully mark processed.
2. Log at WARNING with full sender/subject/Message-ID.
3. If unmatched count in a rolling window exceeds a configured threshold, send an SMTP alert to an ops address (reuses the Jakarta Mail dependency already present for IMAP).
4. Unmatched emails are not auto-retried against rules after a rule-file change — onboarding a fix includes an explicit "clear the flag / move back to INBOX" operator step.
5. A `multi_alarm` split that produces 0 blocks (rule regex no longer matches a changed client format) is treated as **unmatched**, not silently empty — a stale rule should surface, not vanish.

## Retry/Backoff Against the Target API

- `AlarmApiClient` retries a batch POST with exponential backoff within the same poll cycle on connection failure or 5xx.
- All retries exhausted → leave the source email(s) unprocessed; the next poll cycle retries the whole fetch-and-send flow (idempotent thanks to handle-based upsert — no separate outbox/retry-queue table needed).
- **Per-cycle batch cap** (e.g. process at most N emails per poll) plus a **circuit breaker**: after several consecutive fully-failed poll cycles, extend the poll interval exponentially until a POST succeeds again, then reset. Bounds API load and log noise during an extended target-API outage without losing anything (backlog just accumulates safely in the mailbox).
- Circuit-breaker-open and retry-exhaustion events reuse the same SMTP alert path as unmatched-sender alerts.

## Config (`config.properties`)

```properties
# --- Mailbox (IMAP) ---
mail.imap.host=outlook.office365.com
mail.imap.port=993
mail.imap.ssl=true
mail.imap.user=alarms-bridge@example.com
mail.imap.password=changeme
mail.imap.folder=INBOX
mail.poll.interval.seconds=60
mail.processed.strategy=FLAG_AND_LOCAL
mail.processed.store.path=./data/processed-ids.log
mail.unmatched.folder=Unmatched

# --- Rule engine ---
rules.directory=./rules
rules.reload.on.change=true
rules.default.timezone=UTC

# --- Target API (NiagaraSQLAlarmServer) ---
api.url=http://localhost:5555/api/endpoint
api.connect.timeout.ms=5000
api.read.timeout.ms=10000
api.retry.max.attempts=5
api.retry.backoff.initial.ms=2000
api.retry.backoff.multiplier=2.0

# --- Dead-letter / alerting ---
alert.smtp.host=smtp.example.com
alert.smtp.from=mailalarmbridge@example.com
alert.smtp.to=ops-team@example.com
alert.unmatched.threshold.count=3
alert.unmatched.threshold.window.minutes=15
```

## Onboarding a New Client (no code changes)

1. Save a few real sample emails as `.eml` fixtures under `src/test/java/fixtures/<client-id>/`.
2. Write `rules/<client-id>.yaml` against those fixtures.
3. Add a `RuleEngineTest` case asserting the fixture produces the expected `AlarmRecord` list, including a note on whether a "normal" follow-up exists for this client.
4. Drop the YAML file into the running app's `rules/` directory — picked up live via `rules.reload.on.change`, no restart needed.
5. If any of that client's past emails were dead-lettered as unmatched, clear the flag/move back to INBOX to let them flow through the new rule.

## Security Note

Same posture as the existing API: `/api/endpoint` has zero authentication, so MailAlarmBridge must be deployed on the same trusted network segment as `NiagaraSQLAlarmServer`, not across the open internet. Document this in the new repo's README/INSTALL, same as the existing one presumably does.

## Testing Strategy

- **Fixture-based rule engine tests** per client: load real anonymized `.eml` files via Jakarta Mail's `MimeMessage(Session, InputStream)`, run through `RuleEngine.match()` + extraction, assert exact `AlarmRecord` output — including that the synthesized handle is stable across repeated runs of the same fixture, and matches between an offnormal/normal fixture pair where that correlation is expected to exist.
- **`DateNormalizer`** table-driven tests across the format chain, DST-boundary cases, and garbage-input fallback (never throws).
- **`HandleSynthesizer`** determinism tests: same inputs → same hash; different `point_name` → different hash; namespaced per `client_id`.
- **`AlarmApiClient`** tests using a local `com.sun.net.httpserver.HttpServer` stub (same class the target app itself uses) simulating 200/400/500/connection-refused, asserting retry/backoff counts and correct processed-marking behavior.
- **Manual end-to-end smoke test**, documented in the new repo's `INSTALL.md`: point the bridge at a disposable mailbox + a local `NiagaraSQLAlarmServer` instance, send a test email matching a sample rule, confirm the row lands correctly in MySQL.

## Critical Reference Files (existing repo)

- `pom.xml` — Maven/assembly conventions to mirror.
- `src/main/java/NiagaraSQLAlarmServer.java` — exact target-API field names/casing, date-fallback behavior, upsert logic the bridge must stay compatible with.
- `src/main/java/Utils.java` — `config.properties` loader pattern to mirror.
- `config.properties.example` — config-file documentation style to mirror.

## New Highest-Risk Components (new repo)

- `rules/RuleEngine.java` — the core novel component; getting match/extraction/multi-alarm-splitting right is the highest-leverage piece of this app.
- `normalize/DateNormalizer.java` and `normalize/HandleSynthesizer.java` — directly determine whether dedupe/upsert against the target API's `UNIQUE KEY(Handle)` and date-fallback behavior actually work correctly; these are the two quirks in the target API most likely to bite silently if gotten wrong.
