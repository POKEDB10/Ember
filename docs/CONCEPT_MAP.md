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

Later concept mappings are added with the phase that implements the corresponding code.
