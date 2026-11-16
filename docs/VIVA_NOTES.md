# Viva notes

## Phase 0: scaffold

The scaffold establishes the package root, shared product identity, plain `javac` build, and a zero-dependency test entry point. `AppInfo` keeps the product name and tagline in one place; `EmberScaffold` is a temporary runnable entry point until the server and client are added.

**Key classes:** `AppInfo`, `EmberScaffold`, `EmberTestSuite`.

**Trace:** `scripts/run` compiles main sources, then starts `EmberScaffold.main`; the entry point reads `AppInfo.NAME` and `AppInfo.TAGLINE` and prints the banner.

**Concepts:** packages, classes, constructors, `static`, `final`, command-line entry point.

### Likely questions

1. **Why centralize the app name and tagline?** Both values are used across UI, logs, and documentation. Keeping the runtime values in one class makes a rename easy and avoids inconsistent literals.
2. **Why use plain `javac` instead of Maven or Gradle?** The course constraint is JDK APIs only, and a direct compiler command makes the build steps visible to students. It avoids dependency and build-tool setup for an offline demo.
3. **Why is the test runner zero-dependency?** It works with only the JDK on the demo laptop. As features arrive, small assertions and test cases can be added without a separate test framework.

## Phase 1: transport and security primitives

`FrameCodec` reads the fixed-size header, validates magic, version, and payload length before allocating the payload, then reads it fully. `KvPayloadCodec` encodes escaped UTF-8 fields. `TlsSocketFactory` enables TLS 1.3 with Ember's own truststore and HTTPS endpoint identification, while `Pbkdf2PasswordHasher` stores salted PBKDF2-HMAC-SHA256 hashes at 600,000 iterations and provides a fixed dummy verification hash.

**Key classes:** `WireFrame`, `FrameCodec`, `KvPayloadCodec`, `TlsSocketFactory`, `Pbkdf2PasswordHasher`, `InputValidator`, and the checked `EmberException` hierarchy.

**Trace:** a client creates a `WireFrame`, `FrameCodec` writes its 18-byte header and payload to a TLS socket, the peer checks the header limit before allocating, and `KvPayloadCodec` decodes UTF-8 fields after the frame arrives.

**Concepts:** binary streams, Builder and Factory patterns, `StringBuffer`, `StringTokenizer`, UTF-8, PBKDF2, TLS, regular expressions, checked exceptions.

### Likely questions

1. **Why check the payload length before creating its byte array?** The length comes from untrusted network input. Checking first prevents a peer from asking the client or server to allocate an oversized buffer.
2. **Why does the client use its own truststore and endpoint identification?** The truststore limits trust to the demo server certificate, and endpoint identification checks that the certificate matches the requested host. The TLS loopback test also confirms the JVM default truststore rejects the self-signed Ember certificate.
3. **Why use PBKDF2 and a dummy hash for unknown users?** PBKDF2-HMAC-SHA256 is the JDK-native choice under the no-library rule, with the specified 600,000 work factor. Verifying against a fixed dummy hash keeps the unknown-account path doing password-hash work instead of returning immediately.

## Phase 2: walking skeleton

`EmberServer` accepts TLS connections and gives each connection a named reader task. `AuthService` registers and authenticates accounts through the `StorageEngine` interface. `ClientController` sends commands through one outbound sender and receives events through one inbound receiver; model listeners are notified on the Swing EDT. The headless test registers Alice and Bob, sends “Hello”, observes it at Bob, and waits for Alice's DELIVERED receipt.

**Key classes:** `EmberServer`, `ConnectionHandler`, `AuthService`, `SessionStore`, `InMemoryStorageEngine`, `ClientController`, `ClientStateModel`, `OutboundSenderThread`, `InboundReceiverThread`, `EmberClientApp`.

**Trace:** Alice registers over TLS → the server hashes and stores the account → login success binds user identity to the connection → Alice sends a message → the server assigns sequence values and acknowledges it → Bob receives it and sends a delivery receipt → Alice sees DELIVERED.

**Concepts:** interfaces and DAO, thread pools, platform threads, `wait/notify`, Observer/MVC, Swing EDT, `SwingWorker`, synchronized state.

### Likely questions

1. **Why is identity bound to the connection?** The server sets the authenticated user from the login result and uses that value for later commands. The client does not choose a sender identity in a message frame.
2. **Why does the client use one sender thread and one receiver thread?** One writer prevents frames from interleaving on the socket, and one reader gives a clear place to process server events. The sender queue lets UI actions enqueue work without writing network bytes on the EDT.
3. **Why use a SwingWorker for the connection and keep model callbacks on the EDT?** TLS handshakes and socket work can block, so they must not freeze the interface. Swing component updates belong on the event dispatch thread; the model schedules listener notifications there.

## Phase 3: persistence and hardening

`WalStorageEngine` appends records containing a length, CRC32, record type, and UTF-8 KV payload. Replay applies valid records in order and truncates the file at the first incomplete or checksum-invalid record. Authentication uses a four-permit semaphore around expensive hashes; failed logins feed `UserLockManager`, while `TokenBucket` limits messages and `SecurityAuditLogger` appends escaped JSON events without credentials.

**Key classes:** `WalStorageEngine`, `WalRecord`, `AutoSaveThread`, `SecurityAuditLogger`, `UserLockManager`, `AuthRateLimiter`, `TokenBucket`, `AuthService`.

**Trace:** a registered user and message are appended to the WAL → the server closes → a fresh server replays the WAL → the user can log in and the message is present → a torn tail is detected and truncated without discarding earlier records.

**Concepts:** CRC32, file channels, WAL replay, synchronization, `Semaphore`, token bucket, daemon threads, JSON escaping.

### Likely questions

1. **How does WAL recovery handle a process stopping during a write?** Each record carries its byte length and CRC32. If the last record is short or its checksum does not match, replay stops at that record's starting offset and truncates the tail, preserving earlier complete records.
2. **Why use a per-username lockout but disable per-IP throttling on loopback?** Lockout limits repeated password guesses for one account. Demo clients all share the loopback address, so per-IP throttling there would throttle legitimate participants together; the code leaves it off for loopback and still rate-limits messages per user.
3. **What does the audit logger deliberately omit?** It records event names, usernames, timestamps, and source labels, with control characters escaped to keep one event per JSON line. Passwords and session tokens are never fields in its API.

## Phase 4: core messaging

`ClientController` writes outgoing message commands to `LocalOutbox` before placing them on the socket writer queue. `ReconnectManager` retries with exponential backoff and jitter; after a session-token resume, the server sends messages after the client's global sequence and then the client drains its saved outbox. `EmberServer.ConnectionHandler` enforces direct conversation membership and group membership, handles duplicate resends, serves bounded history, and aggregates per-recipient group receipts. Presence snapshots let newly authenticated clients see who is already online; typing and heartbeat use dedicated events.

**Key classes:** `LocalOutbox`, `ReconnectManager`, `ClientController`, `ClientStateModel`, `GroupChat`, `GroupService`, `GroupReceiptTracker`, `EmberServer`, and `StorageEngine`.

**Trace:** a message is framed and stored locally → the server validates the connection identity and conversation/group membership → WAL storage assigns order and saves the message → the sender receives an ACK and recipients receive it or get it in a later sync → recipient receipts update the sender's SENT/DELIVERED/READ state.

**Concepts:** idempotency, producer queue, exponential backoff, TLS session resume, bounded paging, RBAC, fan-out, synchronized aggregation, observer events.

### Likely questions

1. **How does duplicate resend avoid creating a second message?** The server looks up the authenticated sender and client message ID before appending. A matching conversation and body receive the original acknowledgement; reusing the ID for different content is rejected.
2. **How does a client recover after a disconnect?** It reconnects with bounded exponential backoff, resumes with its saved session token and last global sequence, processes missing events, then sends frames still present in its durable local outbox. The integration tests verify an offline message appears once after resume.
3. **Why are group receipts counted on the server?** The server knows the current recipient set and authenticates each receipt against group membership. It reports delivered/read counts and advances status only when every recipient has reached that state, avoiding a client claiming completion based on its own view.

## Phase 5: chat UI system

`ThemeManager` owns the dark and light palettes and `FontResolver` chooses the first supported font. `ChatWorkspacePanel` composes the left conversation list, main conversation view, and settings card. Message bodies use a wrapping `JTextArea` inside a custom painted rounded panel; client-model events update the list, presence line, typing line, receipts, and messages on the Swing EDT. Tray alerts fall back to an in-app `ToastBanner` when the system tray is unavailable.

**Key classes:** `ThemeManager`, `FontResolver`, `RoundedPanel`, `AvatarComponent`, `ChatBubblePanel`, `BadgeLabel`, `AuthPanel`, `ChatListPanel`, `ConversationPanel`, `SettingsPanel`, `ToastBanner`, `NotificationService`, and `ChatWorkspacePanel`.

**Trace:** a login action submits credentials through `AuthPanel` → `ClientController` writes the auth command on its sender thread → `ClientStateModel` receives the auth event → `EmberClientApp` switches to the workspace → message-model notifications cause the conversation panel to repaint wrapped bubbles and receipt marks.

**Concepts:** component composition, `Graphics2D`, Swing layouts, key/focus/document/action listeners, `CardLayout`, `Swing Timer`, Observer/MVC, EDT confinement.

### Likely questions

1. **Why use a `JTextArea` for each message bubble instead of an HTML label?** `JTextArea` wraps plain text and avoids the HTML-label emoji wrapping issue on scaled displays. It keeps hostile text literal rather than interpreting markup.
2. **How does theme switching update the interface?** `ThemeManager` changes shared palette tokens, then each workspace panel applies those tokens to its controls and repaints dynamic components. Keeping colors centralized makes dark/light behavior consistent.
3. **How are notifications shown when the window is not focused?** `NotificationService` uses the desktop tray when supported. If the platform has no tray or refuses the tray icon, `ToastBanner` presents the same message inside the app.

## Phase 6: Campus Pulse core

`SocialFeedService` applies text validation, media ownership checks, and object lookup rules over the `StorageEngine`. `ImageValidator` checks the extension and signature, asks an `ImageReader` for dimensions before decoding, rejects oversized images, then re-encodes a fresh RGB JPEG and thumbnail. The client has feed, profile, post-card, and comment-detail views; file reading and Base64 preparation happen in workers away from the EDT.

**Key classes:** `UserProfile`, `MediaAsset`, `SocialPost`, `PostComment`, `ImageValidator`, `SocialFeedService`, `InMemoryStorageEngine`, `WalStorageEngine`, `CampusPulsePanel`, `ProfilePanel`, `PostCardPanel`, `PostDetailDialog`.

**Trace:** an authenticated user selects a photo → a worker reads it → `CMD_MEDIA_UPLOAD` sends it → the server checks its signature and header dimensions, re-encodes it, and stores UUID-named files → the owner creates a post → another signed-in user pulls the public feed, fetches media linked to that post, and adds a comment → the WAL restores the profile, follow, media metadata, post, like, and comment after restart.

**Concepts:** `ImageReader` and `ImageInputStream`, image header validation, UUID file paths, Base64 protocol fields, WAL record variants, `SwingWorker`, observer notifications, object-level authorization.

### Likely questions

1. **Why check image dimensions before decoding?** Image dimensions come from the encoded file header and are available through `ImageReader` without allocating the full pixel array. Ember rejects either side above 4096 or more than 16 million pixels before calling `read(0)`, which limits image-bomb memory use.
2. **Why does the server rename uploaded files?** User-provided names are used only for extension validation and are never resolved as paths. The server uses generated UUID names in its media directory, preventing traversal through a crafted upload name.
3. **How does the server stop a user from changing another profile or fetching arbitrary media?** Profile update requires the submitted profile ID to match the identity bound to the connection. Media lookup requires a real media record linked to a published post; missing or unlinked media and posts return 404, and the TLS integration tests check these cases.
