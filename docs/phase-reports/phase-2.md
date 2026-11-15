# PHASE 2 REPORT

Calendar: planned Tue 17 Nov 2026; status ahead by 2 calendar days (current date Sun 15 Nov 2026); scope changes: none.

Files created/changed: `src/main/java/edu/ember/common/model/Message.java`, `MessageStatus.java`, `UserRecord.java`; `src/main/java/edu/ember/server/storage/StorageEngine.java`, `InMemoryStorageEngine.java`; `src/main/java/edu/ember/server/EmberServer.java`, `AuthService.java`, `SessionStore.java`; `src/main/java/edu/ember/client/ClientController.java`, `ClientStateModel.java`, `ClientModelListener.java`, `OutboundSenderThread.java`, `InboundReceiverThread.java`, `EmberClientApp.java`; `scripts/run-server.bat`, `run-server.sh`, `run-client.bat`, `run-client.sh`; test and living documentation updates.

## How to build and run

Windows:

```text
scripts\gen-keys.bat
scripts\run-server.bat --port 7443
scripts\run-client.bat --host localhost --port 7443 --profile alice
scripts\run-tests.bat
```

Start another client with `--profile bob`. In the login window, register each profile, then select a recipient in the chat header and send a message. Linux/macOS uses matching `.sh` scripts, but those remain HUMAN-VERIFY on that platform.

## Gate checks

Command: `& .\scripts\build.bat`

Raw output:

```text
Build passed.
```

Result: PASS; all code compiles with the required Java 17 release and warnings-as-errors flags.

Command: `& .\scripts\run-tests.bat` (loopback permission enabled)

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
Ember tests: 7 passed, 0 failed.
```

Result: PASS. The integration test starts an ephemeral TLS server and two client controllers, registers Alice and Bob, sends `Hello`, observes it at Bob, and confirms Alice receives SENT then DELIVERED. Clients and server close in `finally`.

## File status

TESTED (compiled): every Java source and test source under `src/`. TESTED (executed): all seven `EmberTestSuite` cases, including the real two-client TLS exchange. `scripts/build.bat` and `scripts/run-tests.bat` were executed. UNTESTED: the new `scripts/run-server.bat`, `run-client.bat`, and their `.sh` counterparts were not manually launched; the Swing window itself has not been visually exercised. Documentation files have not been machine-tested. Linux/macOS shell scripts remain HUMAN-VERIFY.

## Viva pack

1. **How does the server know who sent a message?** Login sets the authenticated user on the server-side connection handler. The sender ID is taken from that connection context; the send command does not supply a trusted sender field.
2. **How does the test prove delivery rather than just sending?** The test waits for Bob's message event and then waits for Alice's model to advance from SENT to DELIVERED after Bob's client acknowledges receipt.
3. **Why are socket reads and writes on dedicated threads?** Socket operations can block and would freeze Swing if run on the EDT. One writer serializes frames, and one reader processes inbound events and schedules model notifications onto the EDT.

These questions and answers are appended to `docs/VIVA_NOTES.md`.

## Owner review

- R1: trace `EmberServer.ConnectionHandler` routing and verify the server derives the sender from its authenticated connection.
- R2: read `ClientController`, `ClientStateModel`, and sender/receiver threads; explain how the test observes SENT then DELIVERED.
- R3: read `AuthService`, `StorageEngine`, and `InMemoryStorageEngine`; explain why storage is behind an interface and why this phase is not restart-persistent.
- R4: run `EmberClientApp` with the server and inspect EDT use; explain the `SwingWorker` connection task.
- R5: review the Phase 6 feed/profile package responsibilities; no social feed behavior exists yet.
- R6: run the headless suite twice and inspect cleanup in `testWalkingSkeleton`; confirm the server uses an ephemeral port.

Known gaps: no WAL or restart recovery; no lockout or rate limits; session resume and sync are Phase 4; groups, presence, typing, read receipts, and UI polish are not complete. The Swing app compiles but has not had a manual desktop walkthrough. Shell scripts remain HUMAN-VERIFY on Linux/macOS.

Next phase: Phase 3 — Persistence and hardening.
