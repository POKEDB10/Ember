Phase 0: PASSED (completed before this run)

Last passed phase: 3.
Current phase: 4 (Core messaging).
Done: Phases 0–2; Phase 3 WAL persistence/recovery, audit JSON, lockout, message throttling, four-operation hash cap, and passing restart, torn-tail, CRC, flood, and identity tests.
Not done: Phase 4 outbox, reconnect sync, groups, presence, typing, and complete receipt rules.
Open issues: Shell scripts need HUMAN-VERIFY on Linux/macOS. The Swing shell compiles but has not been manually exercised in a desktop session.
SCOPE CHANGES: none.
Exact next steps: add idempotency, durable local outbox, reconnect/backoff and sync, history pull, full READ receipts, presence/typing/heartbeat, groups and roles, then negative object-authorization tests.

Phase 1: PASSED. Report: `docs/phase-reports/phase-1.md`. Commit `1a37b6f`, tag `phase-1-pass`.
Phase 2: PASSED. Report: `docs/phase-reports/phase-2.md`. Checkpoint follows after the phase gate and repository hygiene checks.
Phase 3: PASSED. Report: `docs/phase-reports/phase-3.md`. Checkpoint follows after the phase gate and repository hygiene checks.
