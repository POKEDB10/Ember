# Implementation

## Modules

Phase 0 provides the product constants, named logging helper, build scripts, and zero-dependency test runner. Phase 1 adds the binary protocol (`WireFrame`, `FrameCodec`, `FrameFactory`, `KvPayloadCodec`), TLS socket creation (`TlsSocketFactory`), PBKDF2 password hashing, input validation, and checked application exceptions.

Phase 2 adds an in-memory storage implementation, account registration and login, connection-bound authenticated identity, server-assigned message ordering, direct message fan-out, sender ACK, recipient DELIVERED receipt, a Swing login/chat shell, and dedicated client sender/receiver threads.

Phase 3 adds `WalStorageEngine` with CRC32 verification and torn-tail truncation, `AutoSaveThread`, `SecurityAuditLogger`, username lockout, per-user message token buckets, loopback-aware IP throttling, and a semaphore limiting concurrent password hash work to four operations.

Phase 4 extends messaging with client-generated idempotency keys, server-assigned conversation/global sequences, a framed local outbox, token-based resume and sync, authorized history pulls, heartbeat, presence snapshots and updates, rate-limited typing events, group roles/fan-out, and aggregated delivered/read receipts. The client retries connections with bounded exponential backoff and jitter. The test suite exercises offline delivery after session resume, one-copy duplicate resends, WAL recovery of an undelivered message, four-member group receipts, and negative authorization cases for messages, receipts, history, typing, and group membership changes.

Phase 5 adds retro theme tokens (Ember Amber and Ember Paper, shared with the website), a resolved font fallback, reusable rounded panels, initials avatars, wrapped text bubbles, message receipt marks, a searchable conversation list, an authentication panel, a conversation composer, settings, timed toasts, and system-tray notification fallback. The client includes Ctrl+D/Ctrl+F shortcuts, a reconnect banner, and a developer network-drop control. Offscreen screenshots are generated at 1280×720 and 1920×1080 in both themes; the visual verifier checks message wrapping and non-empty screenshot output.

Phase 6 adds public user profiles, follow/unfollow, safe PNG/JPEG upload, UUID-named media storage, 300-pixel thumbnails, feed posts, likes, comments, and the Campus Pulse, profile, post-card, and post-detail views. `ImageValidator` checks file size, extension, and signature, reads width and height through `ImageReader` before decode, rejects dimensions above 4096 or 16 million pixels, then re-encodes a fresh RGB JPEG. Upload frames use a separate bounded limit and allow large payloads only for `CMD_MEDIA_UPLOAD`; the client reads files and prepares Base64 outside the EDT. Social metadata is persisted as WAL records. Profile updates are self-only, and unpublished or missing media/post objects return 404.

Phase 7 adds expiring messages, replies, reactions, edit/delete, private accounts with follow approval, blocking and rate-limited reports, message pins/stars/search, expiring stories, group polls, group information, an admin report/unlock panel, and message forwarding. The WAL stores these changes and compacts its event history into a checksummed GZIP snapshot. A forwarded message receives a new ID and independent body state. The demo server enables administrators only when usernames are passed with `--admin-user`.

Phase 8 adds `AttackSimulator` for hostile protocol inputs over TLS, `ConcurrencyStressHarness` for ten concurrent headless clients and 500 messages per run (run twice), and `DemoSeeder` for Alice/Bob/Charlie, a study group, poll, sample post, and story. `EmberServer --demo-seed` seeds an empty store and assigns Alice the administrator role for the demo launch. Matching `run-demo` scripts start the local server and three clients. `package-demo` creates runnable JARs and app-images with `jpackage`; the bundled client was launched with no JDK on `PATH`.

Phase 9 turns Ember into a hosted website plus desktop app that work across devices. The server keeps a set of live connections per user, so one account can be signed in on several devices and a sent message reaches the sender's other devices too. `WebGateway` (Adapter pattern) lets browsers use the same server: it serves the static web client and images, and upgrades a connection to a WebSocket that carries the unchanged binary frames, so every existing authorization rule applies to web users. WebSocket traffic is compressed with `java.util.zip.Deflater`/`Inflater`, and message size is checked while a message arrives. On the client side, the `ServerLink` interface has two implementations, a direct TLS socket and a WebSocket using the JDK HTTP client, so the same Swing app connects locally or to the hosted website (polymorphism). For free hosting, `WalStorageEngine` accepts a `StorageReplica` (Strategy pattern): `BlobStoreReplica` batches WAL records every two seconds, GZIP-compresses and AES-256-GCM-encrypts them, and uploads them through the `BlobStore` interface, implemented for rootz.so by `RootzBlobStore` (JDK `HttpClient`) and by an in-memory fake in the tests. A wiped server rebuilds its state from the newest snapshot and later logs. `MiniJson` is a small recursive-descent JSON parser for the REST replies. The server also gained a web-only mode for container hosting, a `/health` endpoint for uptime monitors, a configurable frame cap, and client-address handling behind a proxy.

| Concept | Class | File |
|---|---|---|
| Builder and immutable frame | `WireFrame.Builder`, `WireFrame` | `common/protocol/WireFrame.java` |
| Length-prefixed binary I/O | `FrameCodec` | `common/protocol/FrameCodec.java` |
| UTF-8 text and escaping | `KvPayloadCodec` | `common/protocol/KvPayloadCodec.java` |
| Strategy interface and password hashing | `PasswordHasher`, `Pbkdf2PasswordHasher` | `common/security/PasswordHasher.java`, `Pbkdf2PasswordHasher.java` |
| TLS and endpoint identity | `TlsSocketFactory` | `common/security/TlsSocketFactory.java` |
| Theme strategy and font fallback | `ThemeManager`, `FontResolver` | `client/ui/ThemeManager.java`, `FontResolver.java` |
| Custom painting and Swing composition | `RoundedPanel`, `AvatarComponent`, `ChatBubblePanel` | `client/ui/` |
| MVC views and event listeners | `AuthPanel`, `ChatListPanel`, `ConversationPanel`, `SettingsPanel` | `client/ui/` |
| Notification fallback | `NotificationService`, `ToastBanner` | `client/ui/` |
| Header-first image safety and thumbnail generation | `ImageValidator`, `SocialFeedService` | `common/security/ImageValidator.java`, `server/SocialFeedService.java` |
| Social persistence and feed authorization | `StorageEngine`, `WalStorageEngine` | `server/storage/StorageEngine.java`, `WalStorageEngine.java` |
| Feed/profile/post/comment UI | `CampusPulsePanel`, `ProfilePanel`, `PostCardPanel`, `PostDetailDialog` | `client/ui/` |
| Background upload preparation | `CampusPulsePanel`, `ClientController` | `client/ui/CampusPulsePanel.java`, `client/ClientController.java` |
| Private account and block authorization | `SocialFeedService`, `FollowStatus` | `server/SocialFeedService.java`, `common/model/FollowStatus.java` |
| Reports and admin actions | `ModerationReport`, `AdminConsolePanel` | `common/model/ModerationReport.java`, `client/ui/AdminConsolePanel.java` |
| TTL stories and viewer | `StoryItem`, `StoryViewerDialog` | `common/model/StoryItem.java`, `client/ui/StoryViewerDialog.java` |
| Group polls and 2D vote matrix | `PollData`, `ConversationPanel` | `common/model/PollData.java`, `client/ui/ConversationPanel.java` |
| Snapshot compaction and crash recovery | `WalStorageEngine.compact` | `server/storage/WalStorageEngine.java` |
| In-chat search and deep-copy forwarding | `SearchEngine`, `Message` copy constructor | `client/SearchEngine.java`, `common/model/Message.java` |
| TLS attack scenarios and identity spoofing proof | `AttackSimulator` | `tools/AttackSimulator.java` |
| Concurrent client ordering and duplicate checks | `ConcurrencyStressHarness` | `tools/ConcurrencyStressHarness.java` |
| Reproducible demo accounts and social fixtures | `DemoSeeder` | `tools/DemoSeeder.java` |
| JAR and bundled app-image build | `package-demo` scripts | `scripts/package-demo.bat`, `scripts/package-demo.sh` |
| UML diagrams from implemented flows | PlantUML sources | `docs/uml/*.puml` |
| Interface and polymorphism (two transports) | `ServerLink`, `ServerLink.TlsLink`, `ServerLink.WebSocketLink` | `client/ServerLink.java` |
| Adapter pattern, HTTP, WebSocket framing, compression | `WebGateway`, `WsInput`, `WsOutput` | `server/WebGateway.java` |
| Strategy pattern for storage replicas | `StorageReplica`, `BlobStore`, `BlobStoreReplica`, `RootzBlobStore` | `server/storage/` |
| Symmetric encryption (AES-256-GCM) and GZIP | `BlobStoreReplica.seal`, `open` | `server/storage/BlobStoreReplica.java` |
| Scheduled background thread and batching | `BlobStoreReplica` uploader | `server/storage/BlobStoreReplica.java` |
| Recursion and collections (JSON parser) | `MiniJson` | `common/util/MiniJson.java` |
| Functional interfaces and lambdas | `RemoteCall`, `WebGateway.MediaSource` | `server/storage/BlobStoreReplica.java`, `server/WebGateway.java` |
| Concurrent maps for multi-device fan-out | `EmberServer.sendToUser`, `removeOnline` | `server/EmberServer.java` |
| Live integration check against rootz.so | `ReplicaCheck` | `tools/ReplicaCheck.java` |

## Security

Use only the claims recorded in `docs/SECURITY.md` and the project claims table. Do not describe planned features as completed.

## Screenshots

Website screenshots (add for the report): login, chat with reply and reactions, group poll, Campus Pulse feed, story viewer, profile, mobile layout, and the paper/green themes.
Desktop shot list: login/register, chat list, conversation, group conversation and poll, Campus Pulse feed, post detail, profile, story viewer, admin console, and settings.
Phase 5 captures: `docs/screenshots/phase-5-dark-1280x720.png`, `phase-5-dark-1920x1080.png`, `phase-5-light-1280x720.png`, `phase-5-light-1920x1080.png`. Remaining shot list: login/register, Campus Pulse feed, post detail, profile, and settings walkthrough.
