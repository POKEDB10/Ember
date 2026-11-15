# Implementation

## Modules

Phase 0 provides the product constants, named logging helper, build scripts, and zero-dependency test runner. Phase 1 adds the binary protocol (`WireFrame`, `FrameCodec`, `FrameFactory`, `KvPayloadCodec`), TLS socket creation (`TlsSocketFactory`), PBKDF2 password hashing, input validation, and checked application exceptions.

Phase 2 adds an in-memory storage implementation, account registration and login, connection-bound authenticated identity, server-assigned message ordering, direct message fan-out, sender ACK, recipient DELIVERED receipt, a Swing login/chat shell, and dedicated client sender/receiver threads.

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
