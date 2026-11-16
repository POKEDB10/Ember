# PHASE 4 REPORT

Calendar: planned Thu 19–Fri 20 Nov 2026; status ahead by 3 calendar days (current date Mon 16 Nov 2026); scope changes: none.

Files created/changed: `client/LocalOutbox.java`, `client/ReconnectManager.java`, Phase 4 client model/controller and Swing reconnect integration; `common/model/GroupChat.java`, `UserRole.java`; `server/GroupService.java`, `GroupReceiptTracker.java`, `EmberServer.java`; `StorageEngine`, in-memory and WAL storage; Phase 4 integration tests; README, architecture, concept map, viva notes, report implementation draft, and progress record.

## How to build and run

Windows:

```text
scripts\build.bat
scripts\run-tests.bat
scripts\run-server.bat --port 7443 --storage server_data --durability strict
scripts\run-client.bat --host 127.0.0.1 --port 7443 --profile alice
```

Linux/macOS uses the matching `.sh` scripts. These scripts have not been run on those platforms and remain HUMAN-VERIFY. The test suite uses ephemeral ports and closes sockets and servers in `finally` blocks.

## Gate checks

Command: `scripts\build.bat`

Raw output:

```text
Build passed.
```

Result: PASS with `--release 17 -encoding UTF-8 -Xlint:all,-serial -Werror`.

Command: `scripts\run-tests.bat` (run with local loopback socket permission)

Raw output:

```text
Build passed.
PASS: KV codec round trip
PASS: bad frame magic rejected
PASS: oversized frames rejected before payload read
PASS: password hashing and dummy verification
PASS: input validation
Verified: default JVM trust store rejected Ember certificate
TLS loopback negotiated: TLSv1.3
PASS: TLS 1.3 loopback frame round trip
PASS: headless registration, message, and delivery
PASS: WAL restart recovers Arabic and emoji records and trims torn tail
PASS: WAL CRC failure truncates invalid tail
PASS: WAL restart restores group members and roles
PASS: server restart restores accounts and messages from WAL
PASS: five bad logins lock account and audit stays one JSON line
PASS: concurrent password hashing is capped at four
PASS: message flood receives 429
PASS: loopback IP limiter remains disabled
PASS: local outbox persists framed commands and reconnect delay is bounded
PASS: resume syncs offline messages once and history, typing, presence, and ping work
PASS: group fan-out aggregates receipts and denies unauthorized object access
Ember tests: 18 passed, 0 failed.
```

Result: PASS. Duplicate resend created one message. A server was stopped after ACK while Bob was offline; after restart the undelivered WAL message synced to Bob once. A resumed offline client received its two pending messages once, history pull returned the stored conversation, and heartbeat, typing, presence, group fan-out, and read aggregation passed. The four-member group reached Delivered 3/3 and Read 3/3. Negative checks covered non-member group and direct sends, non-moderator group membership changes, outsider group history/typing/receipts, and sender receipts.

## File and test status

TESTED (compiled): all Java sources under `src/`. TESTED (executed): all 18 test cases, including the Phase 4 socket flows, undelivered-message recovery, and WAL group-role recovery. `.bat` build and test scripts ran successfully.

UNTESTED: Linux/macOS `.sh` scripts; visual and manual Swing walkthrough at 1280×720 and 1920×1080; reconnect UX after physically stopping the server; notification UI. Packaging is Phase 8.

## Viva pack

1. **How does duplicate resend avoid creating a second message?** The server looks up the authenticated sender and client message ID before appending. A matching conversation and body receive the original acknowledgement; reusing the ID for different content is rejected.
2. **How does a client recover after a disconnect?** It retries with bounded exponential backoff, resumes with its saved token and last global sequence, processes missing events, and drains its durable outbox. The TLS integration test verifies an offline message is restored once.
3. **Why are group receipts counted on the server?** The server authenticates each recipient receipt against group membership and aggregates counts. It advances the sender's message status only when every recipient reaches DELIVERED or READ.

These answers are appended to `docs/VIVA_NOTES.md`.

## Owner review

- R1: trace `CMD_MSG_SEND`, server idempotency, direct/group authorization, group fan-out, history pull, and presence/typing routing in `EmberServer`.
- R2: inspect `LocalOutbox`, `ReconnectManager`, resume/sync handling, receipt state, and EDT listener notifications; explain the recovery integration test.
- R3: read WAL group persistence and explain why a duplicate resend does not append a second message.
- R4: run the minimal client and verify connection-loss status and resume integration; visual rebuild belongs to Phase 5.
- R5: confirm Campus Pulse remains out of scope until Phase 6 and review current security claims.
- R6: rerun the complete test script, read this gate output, and check the per-row object-authorization negatives.

Known gaps: the minimal Swing shell has no designed group info or receipt tooltip yet; those belong to the Phase 5 UI system. Manual reconnect UX, both themes, notification fallback, and Linux/macOS scripts need HUMAN-VERIFY. Campus Pulse is not implemented yet.

Next phase: Phase 5 — chat UI system.
