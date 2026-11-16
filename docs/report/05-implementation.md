# Implementation

## Modules

Phase 0 provides the product constants, named logging helper, build scripts, and zero-dependency test runner. Phase 1 adds the binary protocol (`WireFrame`, `FrameCodec`, `FrameFactory`, `KvPayloadCodec`), TLS socket creation (`TlsSocketFactory`), PBKDF2 password hashing, input validation, and checked application exceptions.

Phase 2 adds an in-memory storage implementation, account registration and login, connection-bound authenticated identity, server-assigned message ordering, direct message fan-out, sender ACK, recipient DELIVERED receipt, a Swing login/chat shell, and dedicated client sender/receiver threads.

Phase 3 adds `WalStorageEngine` with CRC32 verification and torn-tail truncation, `AutoSaveThread`, `SecurityAuditLogger`, username lockout, per-user message token buckets, loopback-aware IP throttling, and a semaphore limiting concurrent password hash work to four operations.

Phase 4 extends messaging with client-generated idempotency keys, server-assigned conversation/global sequences, a framed local outbox, token-based resume and sync, authorized history pulls, heartbeat, presence snapshots and updates, rate-limited typing events, group roles/fan-out, and aggregated delivered/read receipts. The client retries connections with bounded exponential backoff and jitter. The test suite exercises offline delivery after session resume, one-copy duplicate resends, WAL recovery of an undelivered message, four-member group receipts, and negative authorization cases for messages, receipts, history, typing, and group membership changes.

| Concept | Class | File |
|---|---|---|
| Builder and immutable frame | `WireFrame.Builder`, `WireFrame` | `common/protocol/WireFrame.java` |
| Length-prefixed binary I/O | `FrameCodec` | `common/protocol/FrameCodec.java` |
| UTF-8 text and escaping | `KvPayloadCodec` | `common/protocol/KvPayloadCodec.java` |
| Strategy interface and password hashing | `PasswordHasher`, `Pbkdf2PasswordHasher` | `common/security/PasswordHasher.java`, `Pbkdf2PasswordHasher.java` |
| TLS and endpoint identity | `TlsSocketFactory` | `common/security/TlsSocketFactory.java` |

## Security

Use only the claims recorded in `docs/SECURITY.md` and the project claims table. Do not describe planned features as completed.

## Screenshots

Shot list: login/register, chat list, conversation, group conversation, Campus Pulse feed, post detail, profile, and settings.  
Placeholders: `[Figure 1: Login and registration]`, `[Figure 2: Conversation]`, `[Figure 3: Campus Pulse feed]`.
