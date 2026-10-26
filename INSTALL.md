# MailAlarmBridge - Install & Operate

## Prerequisites

- Java 17
- Maven
- A reachable `NiagaraSQLAlarmServer` instance (this app forwards to its `/api/endpoint`)
- An IMAP/IMAPS mailbox the bridge can poll (a dedicated mailbox, not a personal inbox)

## Building

```
mvn clean package
```

Produces `target/MailAlarmBridge.jar` (needs the dependency jars on the classpath) and
`target/MailAlarmBridge-jar-with-dependencies.jar` (self-contained - use this one to run).

## Configuring

Copy `config.properties.example` to `config.properties` and fill in your mailbox credentials,
rule directory, target API URL, and SMTP alert address. `config.properties` is never committed
to source control (see `.gitignore`) - keep real credentials out of git.

`mail.imap.host`, `mail.imap.user`, and `mail.imap.password` are required; the app refuses to
start if any are blank. Every other key has a working default (see the example file's comments).

## Running

```
java -jar target/MailAlarmBridge-jar-with-dependencies.jar
```

Runs an infinite poll loop at `mail.poll.interval.seconds`, logging to the console.

## Admin Page (Client Rule Builder)

While the app is running, browse to `http://127.0.0.1:8081/` (or whatever `admin.port` is set
to) for a form-based builder that creates/edits the per-client `rules/*.yaml` files without
hand-editing YAML. It is hardcoded to bind `127.0.0.1` regardless of configuration - it is not
reachable from any other host, and has no separate login because of that. Saving a rule writes
it straight into the running app's `rules/` directory; with `rules.reload.on.change=true` (the
default) it takes effect on the next poll cycle with no restart. Set `admin.enabled=false` to
turn the page off entirely.

## Security / Network Note

Same posture as `NiagaraSQLAlarmServer`: `/api/endpoint` has **zero authentication**. Deploy
MailAlarmBridge on the same trusted network segment as `NiagaraSQLAlarmServer`, never across
the open internet. Unlike the target server, this app is an outbound client only (IMAP + HTTP
out) - it does not listen on any port, so no inbound firewall rule is needed for it.

## Onboarding a New Client (no code changes)

1. Save a few real sample emails as `.eml` fixtures under `src/test/java/fixtures/<client-id>/`.
2. Write `rules/<client-id>.yaml` against those fixtures.
3. Add a `RuleEngineTest` case asserting the fixture produces the expected `AlarmRecord` list,
   including a note on whether a "normal" follow-up exists for this client.
4. Drop the YAML file into the running app's `rules/` directory - picked up live via
   `rules.reload.on.change`, no restart needed.
5. If any of that client's past emails were dead-lettered as unmatched (flagged
   `$MailAlarmBridge-Unmatched` or moved to the `Unmatched` folder), clear the flag / move them
   back to INBOX to let them flow through the new rule.

## Manual End-to-End Smoke Test

1. Point `config.properties` at a disposable IMAP mailbox and a local running
   `NiagaraSQLAlarmServer` instance (`api.url=http://localhost:5555/api/endpoint`).
2. Send a test email to that mailbox matching a sample rule (e.g. `rules/north-campus.yaml`'s
   `match.from`/`subject_regex`).
3. Start MailAlarmBridge and wait for a poll cycle.
4. Confirm the row lands correctly in MySQL (`estate` schema, matching `Handle`, and a
   `TimeStamp` correctly converted to UTC).
