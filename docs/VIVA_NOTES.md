# Viva notes

Read this first. Paths are under `src/main/java/edu/ember/` unless they start with `src/` or `docs/`. Numbers come from `docs/results.md`; if asked for a number that is not there, say "we did not measure that".

## Concept → class → file → why

| Concept | Class | File | Why it is there (say this) |
|---|---|---|---|
| Encapsulation | `Message`, `GroupChat` | `common/model/` | Fields are private; changes go through checked, synchronized methods so no thread sees half an update. |
| Inheritance and overriding | `EmberException` and subclasses | `common/exceptions/` | One `catch` turns every subclass into a protocol error code (400/401/403/404/423/429). |
| Abstract class | `QualityPolicy` (+ `DataSaver`, `Auto`, `Max`) | `client/call/QualityPolicy.java` | Shared rules in the parent, numbers per mode in three subclasses. A new mode is one subclass. |
| Interfaces and polymorphism | `ServerLink` (`TlsLink`, `WebSocketLink`) | `client/ServerLink.java` | The same Swing app runs over a TLS socket or the website's WebSocket, chosen at run time. |
| Interfaces for testing | `AudioCodec`, `Clock`, `Transport`, `AudioDevices.Microphone/Speaker` | `client/call/` | Tests use a fake clock and fake devices, so jitter, unplugging and Speaker mode are tested without hardware. |
| Generics | `CallRegistry<D>` | `server/CallRegistry.java` | Works for any device type; tests drive it without sockets. |
| Comparable, equals, hashCode | `AudioFrame` | `client/call/AudioFrame.java` | Frames sort by sequence number in the jitter buffer's `TreeMap`; `equals` agrees with `compareTo`. |
| Records | `CallStats`, `PushSubscription`, `UserRecord` | `client/call/`, `common/model/` | Immutable snapshots: safe to hand to the UI thread. |
| Enums | `QualityMode`, `CallSession.State`, `ThemeManager.Theme` | | Fixed sets of values with behaviour (the theme enum has a constructor). |
| Nested, inner, anonymous classes | `ConnectionHandler` (inner), `WireFrame.Builder` (static nested), listeners | `server/EmberServer.java`, `common/protocol/WireFrame.java` | The inner class needs the server's state; the builder does not. |
| Exceptions with `finally` | `VoiceEngine`, `Pbkdf2PasswordHasher` | `client/call/`, `common/security/` | Devices are always released; key material is wiped. |
| Threads, `wait`/`notify` | `OutboundSenderThread` | `client/` | Producer-consumer: the UI enqueues, one thread writes, the window never freezes. |
| Synchronization without blocking | `JitterBuffer` | `client/call/JitterBuffer.java` | The network thread inserts; the playout thread must get a frame every 20 ms, so it never waits (it conceals instead). |
| Collections | `ConcurrentHashMap` of devices per user, `LinkedList` queue, `TreeMap` jitter buffer | | Each chosen for its access pattern. |
| File handling | `WalStorageEngine`, `CallSettings`, `StatsLog` | `server/storage/`, `client/call/` | CRC-checked log; `.properties` settings; a log that rotates at 256 KB. |
| Swing and events | `CallDialog`, `SettingsPanel`, `ChatWorkspacePanel` | `client/ui/` | Updates on the event thread only. |
| Observer | `ClientModelListener`, `CallStatsListener` | `client/` | Panels and the stats overlay react to changes without polling. |
| Strategy | `PasswordHasher`, `StorageReplica`, `QualityPolicy` | | Swap behaviour without touching callers. |
| Factory | `FrameFactory`, `CodecFactory`, `ServerLink.tls()/webSocket()` | | One place decides which object to build. |
| Command | "Video on website" `AbstractAction` | `client/ui/CallDialog.java` | The action is an object; the same Action could sit on a button, a menu or a key. |
| State | `CallSession.State`, the relay `Ladder` | `client/CallSession.java`, `src/main/resources/web/quality.js` | Behaviour depends on the current state; transitions are explicit (report Figure 7). |
| Builder, Adapter, Facade | `WireFrame.Builder`, `WebGateway`, `ClientController` | | Readable frames; WebSocket ↔ frames; one front door for the UI. |

## 20 likely questions

1. **Why no libraries?** Course rule and a zero-setup demo: only the JDK and a browser. Even the JavaScript tests use Node's built-in runner.
2. **How does one account work on several devices?** The server keeps every connection of each user and delivers each message to all of them, including the sender's other devices.
3. **What does end-to-end encryption mean here?** Message text is encrypted on the sending device with a random key, wrapped for every receiving device (ECDH + HKDF + AES-GCM). The server stores only ciphertext. It still sees metadata: who talks to whom and when.
4. **How can the server forward a call it cannot hear?** Each pair makes a fresh ECDH key in the call; signals are sealed with it. On the relay each sender encrypts packets with a sender key handed out over those pair channels. The server copies bytes and stamps who sent them.
5. **What is the safety code?** Digits from both public keys. Same code on both screens means nobody swapped the keys in the middle, not even the server.
6. **Direct vs relay?** Direct (WebRTC) has the lowest delay. The relay runs inside the website's HTTPS WebSocket, so it works where calling apps are blocked (common in the UAE) and hides IP addresses.
7. **Why does the desktop always use the relay?** Java has no WebRTC and no camera API; we stayed JDK-only, so the desktop is voice-only and relays.
8. **Why ADPCM instead of Opus on the desktop?** The JDK has no Opus. 32 kHz ADPCM is simple, decodes each 20 ms frame on its own, and the website decodes it byte for byte the same (shared test vectors).
9. **What is a jitter buffer?** Packets arrive unevenly; the buffer holds 40–200 ms, plays in sequence order, hides one missing frame, and trims a backlog after a stall. Measured: a 500 ms stall recovered to ≤ 80 ms delay within 3 s.
10. **Why a resampler?** Microphones run at 48 or 44.1 kHz, the codec at 32 or 16 kHz. Our windowed-sinc filter removes sound above the new limit first (tested: ≥ 50 dB down in the stop band), otherwise it would alias into noise.
11. **How does video adapt?** It sends the minimum of camera, mode, the other side's window size and the network. On the relay, a ladder checks every second: trouble → one rung down at once; 6 s calm → one up; failed rungs wait longer.
12. **Did you measure that?** Yes, with a throttling proxy we wrote: at 400 kbit/s trouble showed 1.8 s after the drop and it stepped down within the next second; 180p after 4.8 s; voice kept going. Chrome only, one laptop, synthetic camera.
13. **What is the feature flag?** `--calls-v2` on the server and a "Calls v2" setting on each device. Both must be on; otherwise the older call code runs unchanged. It is the demo's safety switch.
14. **How does hand-off work and why two phases?** Phase 1 gets a 30 s single-use ticket and opens camera and mic; if the user refuses, the desktop keeps the call. Phase 2 swaps the device in one synchronized step; first commit wins. Then the other side re-keys.
15. **What if two tabs press Join at once?** Only the first commit wins; the other gets "not found". A ticket only works on the device it was issued to, and a stolen ticket cannot cancel it (a bug our test found and we fixed).
16. **Why does a `?join=` link not join automatically?** Anyone can send a link. The page only shows a button; the id is format-checked and removed from the address bar.
17. **How do you stop abuse?** Rate limits (messages, logins, rings, call signals, media bytes), five-failure lockout, size limits before reading, server-side permission checks with a negative test per rule.
18. **How is data kept on a free host that wipes its disk?** Every change goes to a CRC-checked log; an encrypted copy (AES-256-GCM) goes to rootz.so every 2 s; a fresh server rebuilds from it.
19. **What did you not test?** Firefox and Safari, real devices unplugged, direct calls on lossy networks, server load on Render, mouth-to-ear delay. They are listed as "not measured" in `docs/results.md`.
20. **What would you do next?** Opus on the desktop (pure-Java decoder), our own TURN server for more direct calls, and hiding more metadata from the server.

## Appendix: notes from each build phase

### Phase 0: scaffold

The scaffold establishes the package root, shared product identity, plain `javac` build, and a zero-dependency test entry point. `AppInfo` keeps the product name and tagline in one place; `EmberScaffold` is a temporary runnable entry point until the server and client are added.

**Key classes:** `AppInfo`, `EmberScaffold`, `EmberTestSuite`.

**Trace:** `scripts/run` compiles main sources, then starts `EmberScaffold.main`; the entry point reads `AppInfo.NAME` and `AppInfo.TAGLINE` and prints the banner.

**Concepts:** packages, classes, constructors, `static`, `final`, command-line entry point.

#### Likely questions

1. **Why centralize the app name and tagline?** Both values are used across UI, logs, and documentation. Keeping the runtime values in one class makes a rename easy and avoids inconsistent literals.
2. **Why use plain `javac` instead of Maven or Gradle?** The course constraint is JDK APIs only, and a direct compiler command makes the build steps visible to students. It avoids dependency and build-tool setup for an offline demo.
3. **Why is the test runner zero-dependency?** It works with only the JDK on the demo laptop. As features arrive, small assertions and test cases can be added without a separate test framework.

### Phase 1: transport and security primitives

`FrameCodec` reads the fixed-size header, validates magic, version, and payload length before allocating the payload, then reads it fully. `KvPayloadCodec` encodes escaped UTF-8 fields. `TlsSocketFactory` enables TLS 1.3 with Ember's own truststore and HTTPS endpoint identification, while `Pbkdf2PasswordHasher` stores salted PBKDF2-HMAC-SHA256 hashes at 600,000 iterations and provides a fixed dummy verification hash.

**Key classes:** `WireFrame`, `FrameCodec`, `KvPayloadCodec`, `TlsSocketFactory`, `Pbkdf2PasswordHasher`, `InputValidator`, and the checked `EmberException` hierarchy.

**Trace:** a client creates a `WireFrame`, `FrameCodec` writes its 18-byte header and payload to a TLS socket, the peer checks the header limit before allocating, and `KvPayloadCodec` decodes UTF-8 fields after the frame arrives.

**Concepts:** binary streams, Builder and Factory patterns, `StringBuffer`, `StringTokenizer`, UTF-8, PBKDF2, TLS, regular expressions, checked exceptions.

#### Likely questions

1. **Why check the payload length before creating its byte array?** The length comes from untrusted network input. Checking first prevents a peer from asking the client or server to allocate an oversized buffer.
2. **Why does the client use its own truststore and endpoint identification?** The truststore limits trust to the demo server certificate, and endpoint identification checks that the certificate matches the requested host. The TLS loopback test also confirms the JVM default truststore rejects the self-signed Ember certificate.
3. **Why use PBKDF2 and a dummy hash for unknown users?** PBKDF2-HMAC-SHA256 is the JDK-native choice under the no-library rule, with the specified 600,000 work factor. Verifying against a fixed dummy hash keeps the unknown-account path doing password-hash work instead of returning immediately.

### Phase 2: walking skeleton

`EmberServer` accepts TLS connections and gives each connection a named reader task. `AuthService` registers and authenticates accounts through the `StorageEngine` interface. `ClientController` sends commands through one outbound sender and receives events through one inbound receiver; model listeners are notified on the Swing EDT. The headless test registers Alice and Bob, sends “Hello”, observes it at Bob, and waits for Alice's DELIVERED receipt.

**Key classes:** `EmberServer`, `ConnectionHandler`, `AuthService`, `SessionStore`, `InMemoryStorageEngine`, `ClientController`, `ClientStateModel`, `OutboundSenderThread`, `InboundReceiverThread`, `EmberClientApp`.

**Trace:** Alice registers over TLS → the server hashes and stores the account → login success binds user identity to the connection → Alice sends a message → the server assigns sequence values and acknowledges it → Bob receives it and sends a delivery receipt → Alice sees DELIVERED.

**Concepts:** interfaces and DAO, thread pools, platform threads, `wait/notify`, Observer/MVC, Swing EDT, `SwingWorker`, synchronized state.

#### Likely questions

1. **Why is identity bound to the connection?** The server sets the authenticated user from the login result and uses that value for later commands. The client does not choose a sender identity in a message frame.
2. **Why does the client use one sender thread and one receiver thread?** One writer prevents frames from interleaving on the socket, and one reader gives a clear place to process server events. The sender queue lets UI actions enqueue work without writing network bytes on the EDT.
3. **Why use a SwingWorker for the connection and keep model callbacks on the EDT?** TLS handshakes and socket work can block, so they must not freeze the interface. Swing component updates belong on the event dispatch thread; the model schedules listener notifications there.

### Phase 3: persistence and hardening

`WalStorageEngine` appends records containing a length, CRC32, record type, and UTF-8 KV payload. Replay applies valid records in order and truncates the file at the first incomplete or checksum-invalid record. Authentication uses a four-permit semaphore around expensive hashes; failed logins feed `UserLockManager`, while `TokenBucket` limits messages and `SecurityAuditLogger` appends escaped JSON events without credentials.

**Key classes:** `WalStorageEngine`, `WalRecord`, `AutoSaveThread`, `SecurityAuditLogger`, `UserLockManager`, `AuthRateLimiter`, `TokenBucket`, `AuthService`.

**Trace:** a registered user and message are appended to the WAL → the server closes → a fresh server replays the WAL → the user can log in and the message is present → a torn tail is detected and truncated without discarding earlier records.

**Concepts:** CRC32, file channels, WAL replay, synchronization, `Semaphore`, token bucket, daemon threads, JSON escaping.

#### Likely questions

1. **How does WAL recovery handle a process stopping during a write?** Each record carries its byte length and CRC32. If the last record is short or its checksum does not match, replay stops at that record's starting offset and truncates the tail, preserving earlier complete records.
2. **Why use a per-username lockout but disable per-IP throttling on loopback?** Lockout limits repeated password guesses for one account. Demo clients all share the loopback address, so per-IP throttling there would throttle legitimate participants together; the code leaves it off for loopback and still rate-limits messages per user.
3. **What does the audit logger deliberately omit?** It records event names, usernames, timestamps, and source labels, with control characters escaped to keep one event per JSON line. Passwords and session tokens are never fields in its API.

### Phase 4: core messaging

`ClientController` writes outgoing message commands to `LocalOutbox` before placing them on the socket writer queue. `ReconnectManager` retries with exponential backoff and jitter; after a session-token resume, the server sends messages after the client's global sequence and then the client drains its saved outbox. `EmberServer.ConnectionHandler` enforces direct conversation membership and group membership, handles duplicate resends, serves bounded history, and aggregates per-recipient group receipts. Presence snapshots let newly authenticated clients see who is already online; typing and heartbeat use dedicated events.

**Key classes:** `LocalOutbox`, `ReconnectManager`, `ClientController`, `ClientStateModel`, `GroupChat`, `GroupService`, `GroupReceiptTracker`, `EmberServer`, and `StorageEngine`.

**Trace:** a message is framed and stored locally → the server validates the connection identity and conversation/group membership → WAL storage assigns order and saves the message → the sender receives an ACK and recipients receive it or get it in a later sync → recipient receipts update the sender's SENT/DELIVERED/READ state.

**Concepts:** idempotency, producer queue, exponential backoff, TLS session resume, bounded paging, RBAC, fan-out, synchronized aggregation, observer events.

#### Likely questions

1. **How does duplicate resend avoid creating a second message?** The server looks up the authenticated sender and client message ID before appending. A matching conversation and body receive the original acknowledgement; reusing the ID for different content is rejected.
2. **How does a client recover after a disconnect?** It reconnects with bounded exponential backoff, resumes with its saved session token and last global sequence, processes missing events, then sends frames still present in its durable local outbox. The integration tests verify an offline message appears once after resume.
3. **Why are group receipts counted on the server?** The server knows the current recipient set and authenticates each receipt against group membership. It reports delivered/read counts and advances status only when every recipient has reached that state, avoiding a client claiming completion based on its own view.

### Phase 5: chat UI system

`ThemeManager` owns the dark and light palettes and `FontResolver` chooses the first supported font. `ChatWorkspacePanel` composes the left conversation list, main conversation view, and settings card. Message bodies use a wrapping `JTextArea` inside a custom painted rounded panel; client-model events update the list, presence line, typing line, receipts, and messages on the Swing EDT. Tray alerts fall back to an in-app `ToastBanner` when the system tray is unavailable.

**Key classes:** `ThemeManager`, `FontResolver`, `RoundedPanel`, `AvatarComponent`, `ChatBubblePanel`, `BadgeLabel`, `AuthPanel`, `ChatListPanel`, `ConversationPanel`, `SettingsPanel`, `ToastBanner`, `NotificationService`, and `ChatWorkspacePanel`.

**Trace:** a login action submits credentials through `AuthPanel` → `ClientController` writes the auth command on its sender thread → `ClientStateModel` receives the auth event → `EmberClientApp` switches to the workspace → message-model notifications cause the conversation panel to repaint wrapped bubbles and receipt marks.

**Concepts:** component composition, `Graphics2D`, Swing layouts, key/focus/document/action listeners, `CardLayout`, `Swing Timer`, Observer/MVC, EDT confinement.

#### Likely questions

1. **Why use a `JTextArea` for each message bubble instead of an HTML label?** `JTextArea` wraps plain text and avoids the HTML-label emoji wrapping issue on scaled displays. It keeps hostile text literal rather than interpreting markup.
2. **How does theme switching update the interface?** `ThemeManager` changes shared palette tokens, then each workspace panel applies those tokens to its controls and repaints dynamic components. Keeping colors centralized makes dark/light behavior consistent.
3. **How are notifications shown when the window is not focused?** `NotificationService` uses the desktop tray when supported. If the platform has no tray or refuses the tray icon, `ToastBanner` presents the same message inside the app.

### Phase 6: Campus Pulse core

`SocialFeedService` applies text validation, media ownership checks, and object lookup rules over the `StorageEngine`. `ImageValidator` checks the extension and signature, asks an `ImageReader` for dimensions before decoding, rejects oversized images, then re-encodes a fresh RGB JPEG and thumbnail. The client has feed, profile, post-card, and comment-detail views; file reading and Base64 preparation happen in workers away from the EDT.

**Key classes:** `UserProfile`, `MediaAsset`, `SocialPost`, `PostComment`, `ImageValidator`, `SocialFeedService`, `InMemoryStorageEngine`, `WalStorageEngine`, `CampusPulsePanel`, `ProfilePanel`, `PostCardPanel`, `PostDetailDialog`.

**Trace:** an authenticated user selects a photo → a worker reads it → `CMD_MEDIA_UPLOAD` sends it → the server checks its signature and header dimensions, re-encodes it, and stores UUID-named files → the owner creates a post → another signed-in user pulls the public feed, fetches media linked to that post, and adds a comment → the WAL restores the profile, follow, media metadata, post, like, and comment after restart.

**Concepts:** `ImageReader` and `ImageInputStream`, image header validation, UUID file paths, Base64 protocol fields, WAL record variants, `SwingWorker`, observer notifications, object-level authorization.

#### Likely questions

1. **Why check image dimensions before decoding?** Image dimensions come from the encoded file header and are available through `ImageReader` without allocating the full pixel array. Ember rejects either side above 4096 or more than 16 million pixels before calling `read(0)`, which limits image-bomb memory use.
2. **Why does the server rename uploaded files?** User-provided names are used only for extension validation and are never resolved as paths. The server uses generated UUID names in its media directory, preventing traversal through a crafted upload name.
3. **How does the server stop a user from changing another profile or fetching arbitrary media?** Profile update requires the submitted profile ID to match the identity bound to the connection. Media lookup requires a real media record linked to a published post; missing or unlinked media and posts return 404, and the TLS integration tests check these cases.

### Phase 7: signature features

Phase 7 extends chat and Campus Pulse with TTL expiry, replies/reactions, edit/delete, private profiles, moderation, pins/stars/search, stories, polls, admin/group information, forwarding, and compacted WAL snapshots. The server continues to authorize every message, group, profile, post, media, story, poll, and admin action against the authenticated connection.

**Key classes:** `ExpiryReaperThread`, `SearchEngine`, `FollowStatus`, `ModerationReport`, `StoryItem`, `StoryViewerDialog`, `PollData`, `AdminConsolePanel`, `GroupInfoDrawer`, `WalStorageEngine`.

**Trace:** a private-account follow request is saved as PENDING → the owner approves it → the follower can see the profile/feed/story → either side blocks and both follow directions are cleared → subsequent DM commands fail authorization. Poll votes and moderation reports are persisted through WAL records; snapshot compaction compresses the event history and rotates the WAL.

**Concepts:** TTL and daemon thread, `Iterator`, `StringTokenizer`, enums, maps/sets, 2D arrays, MVC observers, role checks, `SwingWorker`, `Timer`, GZIP, CRC, atomic file replacement, WAL replay.

#### Likely questions

1. **Why does a private follow use PENDING instead of immediately adding the follower?** The server stores a request state separately from ACCEPTED so feed, profile, and story authorization can consistently require owner approval. Blocking removes both directions of the follow relationship.
2. **How does snapshot compaction avoid losing records if the process stops at the wrong time?** The compressed snapshot is fully written and atomically moved into place before WAL rotation. It records the covered WAL length and checksum, allowing restart to skip a still-present prefix if the process stopped between snapshot replacement and truncation.
3. **Why does forwarding construct a new `Message`?** A forwarded message has a new ID, sender, destination, and timestamp, and does not inherit mutable reactions, reply state, expiry, or sequence values. Editing the copy therefore cannot change the source message.

### Phase 8: proof, demo, and packaging

`AttackSimulator` demonstrates protocol and authorization behavior over real TLS connections; `ConcurrencyStressHarness` drives the client/server path with concurrent headless clients and checks server history. `DemoSeeder` provides an idempotent local fixture, and the scripts build runnable JARs and `jpackage` app-images.

**Key classes:** `AttackSimulator`, `ConcurrencyStressHarness`, `DemoSeeder`, `EmberServer.main`, `ClientController`, `StorageEngine`, `WalStorageEngine`.

**Trace:** the stress harness authenticates ten `ClientController` instances, creates a group and adds members, starts ten sender threads, reads the persisted conversation history, and asserts 500 distinct IDs, increasing conversation sequence numbers, and increasing per-sender indices. Cleanup closes every client and the ephemeral-port server in `finally`.

**Concepts:** TLS integration tests, threads and shared ordering, fixture idempotency, JAR manifests, bundled runtimes, shell/batch automation, and UML.

#### Likely questions

1. **How does the stress harness distinguish message loss from duplicate delivery?** It waits for 500 persisted history entries, then checks all 500 client message IDs are unique and that each sender's numbered messages remain ordered. It also checks every conversation sequence is strictly increasing.
2. **Why does the attack tool use raw frames as well as the normal client controller?** Raw frames can deliberately include malformed headers or forged fields a conforming client would not send. Both paths still communicate with the real local TLS server.
3. **What does `jpackage --type app-image` provide, and what does it not provide?** It creates a runnable application folder with a bundled Java runtime, so launching the packaged client does not need a JDK on `PATH`. It does not eliminate the JDK 21 build requirement or supply Ember's local truststore and server key configuration.
