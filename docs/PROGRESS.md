Phase 0: PASSED (completed before this run)

Last passed phase: 1.
Current phase: 2 (Walking skeleton).
Done: Phase 0 scaffold and checkpoint; Phase 1 TLS key generation, protocol classes, KV codec, TLS factory, PBKDF2 hasher, input validation, exception hierarchy, and passing Phase 1 gates.
Not done: Phase 2 server/client authentication and live chat.
Open issues: The `.sh` scripts are still HUMAN-VERIFY on Linux/macOS. The Phase 0 runner was empty before Phase 1; it now has seven runnable tests.
SCOPE CHANGES: none.
Exact next steps: add the Phase 2 model and server/client skeleton, implement registration/login and connection-bound sessions, create a minimal Swing login and chat window, then prove a live message and delivery acknowledgement over TLS.

Phase 1: PASSED. Report: `docs/phase-reports/phase-1.md`. Commit/tag are created after the gate and repository hygiene checks.
