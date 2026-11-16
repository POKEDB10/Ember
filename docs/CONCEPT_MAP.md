# Concept map

This map will be updated as concepts appear in implemented classes. Phase 0 demonstrates only the truthful entries below.

| Concept | Where it lives |
|---|---|
| Packages, classes, constructors, static/final members | `edu.ember.EmberScaffold`, `edu.ember.common.util.AppInfo` |
| Command-line entry point | `EmberScaffold.main(String[] args)` |
| Zero-dependency test runner | `edu.ember.test.EmberTestSuite` |
| Binary streams and length-prefixed protocol | `common.protocol.FrameCodec`, `WireFrame` |
| Builder and factory patterns | `WireFrame.Builder`, `FrameFactory` |
| StringBuffer, StringTokenizer, UTF-8 | `KvPayloadCodec` |
| Interfaces and Strategy pattern | `PasswordHasher`, `Pbkdf2PasswordHasher` |
| Regular-expression validation | `InputValidator` |
| TLS and cryptographic key derivation | `TlsSocketFactory`, `Pbkdf2PasswordHasher` |
| Checked exception hierarchy | `EmberException` and `common.exceptions` subclasses |
| Producer-consumer queue and wait/notify | `OutboundSenderThread` |
| Client model and observer notifications | `ClientStateModel`, `ClientModelListener` |
| Connection-bound identity and in-memory DAO | `EmberServer`, `AuthService`, `InMemoryStorageEngine` |
| Swing EDT and background work | `EmberClientApp` (`SwingWorker`), `ClientStateModel` |
| CRC, WAL replay, and torn-tail recovery | `WalStorageEngine`, package-private `WalRecord` |
| Rate limiting and lockout | `TokenBucket`, `AuthRateLimiter`, `UserLockManager` |
| Audit logging and JSON escaping | `SecurityAuditLogger` |
| Daemon autosave thread and interruption | `AutoSaveThread` |
| Roles, group membership, and fan-out | `GroupChat`, `GroupService` |
| Exponential backoff and jitter | `ReconnectManager` |
| File-backed local frame queue | `LocalOutbox` |
| Group roles and aggregated receipts | `GroupChat`, `GroupService`, `GroupReceiptTracker` |
| Idempotency and durable producer queue | `EmberServer.ConnectionHandler`, `LocalOutbox` |
| Exponential backoff and session recovery | `ReconnectManager`, `ClientController.resume` |
| Presence, typing, and heartbeat events | `EmberServer.ConnectionHandler`, `ClientStateModel` |
| Authorized history and sequence paging | `StorageEngine.getHistory`, `CMD_HISTORY_PULL` |

Later concept mappings are added with the phase that implements the corresponding code.
