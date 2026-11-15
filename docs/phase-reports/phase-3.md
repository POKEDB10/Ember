# PHASE 3 REPORT

Calendar: planned Wed 18 Nov 2026; status ahead by 3 calendar days (current date Sun 15 Nov 2026); scope changes: none.

Files created/changed: `src/main/java/edu/ember/server/storage/WalStorageEngine.java`, package-private `WalRecord.java`, `src/main/java/edu/ember/server/AutoSaveThread.java`, `SecurityAuditLogger.java`, `UserLockManager.java`, `AuthRateLimiter.java`, `TokenBucket.java`, `AuthService.java`, `EmberServer.java`, `TlsSocketFactory.java`; updated `StorageEngine`, in-memory storage, test suite, README, architecture/security/concept/viva/demo docs, report drafts, and progress record.

## How to build and run

Windows:

```text
scripts\build.bat
scripts\run-tests.bat
scripts\run-server.bat --port 7443 --storage server_data --durability strict
scripts\run-client.bat --host 127.0.0.1 --port 7443 --profile alice
```

Linux/macOS uses the matching `.sh` scripts. These have not been run on those platforms and remain HUMAN-VERIFY. The server binds only to `127.0.0.1`. Its default production entry point writes `server_data/wal/events.wal` and `logs/security-audit.json`.

## Gate checks

Command: `& .\scripts\build.bat`

Raw output:

```text
Build passed.
```

Result: PASS with `--release 17 -encoding UTF-8 -Xlint:all,-serial -Werror`.

Command: `& .\scripts\run-tests.bat` (run with local loopback socket permission)

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
PASS: server restart restores accounts and messages from WAL
PASS: five bad logins lock account and audit stays one JSON line
PASS: concurrent password hashing is capped at four
PASS: message flood receives 429
PASS: loopback IP limiter remains disabled
Ember tests: 14 passed, 0 failed.
```

Result: PASS. Tests use ephemeral TLS ports and close servers and clients in `finally`. The restart test closes a running WAL-backed server, starts a fresh server on the same data directory, logs back in, and checks the persisted user and message. Separate checks verify a CRC-invalid record is truncated and a torn final record does not discard earlier data.

## File and test status

TESTED (compiled): all Java sources under `src/`. TESTED (executed): all 14 test cases, including WAL restart/torn-tail/CRC checks, audit escaping, five-failure lockout, a concurrency probe for the four-hash semaphore, and an actual network flood that receives 429. `scripts/build.bat` and `scripts/run-tests.bat` were executed.

UNTESTED: `.sh` scripts on Linux/macOS; interactive Swing desktop walkthrough; visual UI checks. The report, README, and living documentation are not machine-tested.

## Viva pack

1. **How does WAL recovery handle a process stopping during a write?** Each record carries its byte length and CRC32. Replay stops at the first short or invalid record and truncates from that record's starting position, preserving earlier complete records.
2. **Why disable per-IP throttling for loopback demos?** Every demo client shares the same loopback IP, so an IP limit would punish the whole group together. Per-username lockout remains active and message floods are limited per authenticated user.
3. **How do the tests verify recovery and hardening?** They restart a WAL-backed server and confirm an account can log in again and its message remains. Additional tests append a torn tail, corrupt a CRC, fail a password five times, inspect escaped one-line audit JSON, and flood messages until the server returns 429.

These answers are appended to `docs/VIVA_NOTES.md`.

## Owner review

- R1: read `WalStorageEngine` replay/write paths and `EmberServer.ConnectionHandler`; trace a record from append through restart and CRC validation.
- R2: read the outbox-free Phase 2 client model and note the Phase 4 reconnect work still required.
- R3: run lockout, hash semaphore, audit, WAL, torn-tail, and CRC tests; explain why the server keystore and secrets are ignored.
- R4: run the Swing client against the server and inspect that socket work stays in `SwingWorker`/reader/writer threads.
- R5: review the still-future image and private-account requirements in `docs/SECURITY.md`.
- R6: run the test suite, inspect the exact gate output, and review `docs/phase-reports/phase-3.md` and `docs/PROGRESS.md`.

Known gaps: local client outbox, reconnect/sync, group messaging, presence, typing, full read-receipt conditions, object-level negative tests, and Campus Pulse are not implemented. Expired message purge and snapshot compaction are also future work.

Next phase: Phase 4 — Core messaging.
