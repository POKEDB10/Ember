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
| Swing theme tokens and font fallback | `client.ui.ThemeManager`, `FontResolver` |
| Reusable custom-painted components | `RoundedPanel`, `AvatarComponent`, `ChatBubblePanel`, `BadgeLabel` |
| Swing panels, focus, key and document listeners | `AuthPanel`, `ChatListPanel`, `ConversationPanel`, `SettingsPanel` |
| Timed notification and system tray fallback | `ToastBanner`, `NotificationService` |
| Header-first image validation, image readers, and RGB conversion | `ImageValidator`, `SocialFeedService` |
| UUID paths, file APIs, and media metadata | `SocialFeedService`, `MediaAsset`, `WalStorageEngine` |
| Feed/profile/post model and authorization | `SocialFeedService`, `UserProfile`, `SocialPost`, `PostComment` |
| Like sets and follow relationships | `InMemoryStorageEngine`, `WalStorageEngine` |
| Background image selection/upload and Swing rendering | `CampusPulsePanel`, `PostCardPanel`, `ClientController` |
| Comment observer view and plain text | `PostDetailDialog`, `ClientStateModel` |
| Opcode-specific large frames | `FrameCodec`, `InboundReceiverThread`, `EmberServer.ConnectionHandler` |

Later concept mappings are added with the phase that implements the corresponding code.

| Concept | Where it lives |
|---|---|
| TTL expiry, iterator cleanup, daemon scheduling | `ExpiryReaperThread`, `Message.expiresAtMs` |
| Tokenized in-chat search | `SearchEngine`, `StringTokenizer` |
| Per-user pins and stars | `Message`, `StorageEngine`, WAL pin/star records |
| Private profile authorization and pending approval | `FollowStatus`, `SocialFeedService`, `ProfilePanel` |
| Moderation reports and role-gated admin actions | `ModerationReport`, `AdminConsolePanel`, `EmberServer` |
| Expiring stories and timer-driven viewer | `StoryItem`, `StoryViewerDialog`, `--story-ttl-sec` |
| 2D vote matrix and per-member poll updates | `PollData`, `CMD_POLL_CREATE`, `CMD_POLL_VOTE` |
| Deep-copy message forwarding | `Message(Message source, ...)`, `ClientController.forwardMessage` |
| GZIP snapshot rotation and recovery | `WalStorageEngine.compact`, CRC-protected snapshot and covered-prefix check |
| Integration attack simulation over TLS | `edu.ember.tools.AttackSimulator`, `TlsSocketFactory`, `FrameCodec` |
| Concurrent clients, exactly-once IDs, server sequence order | `edu.ember.tools.ConcurrencyStressHarness`, `ClientController`, `StorageEngine.getHistory` |
| Idempotent demo fixtures and generated media | `edu.ember.tools.DemoSeeder`, `WalStorageEngine`, `MediaAsset` |
| Command-line demo mode and runtime packaging | `EmberServer --demo-seed`, `scripts/package-demo`, `jpackage` |
| UML use case, class, sequence, state, and activity diagrams | `docs/uml/*.puml` based on implemented source packages |
| Interfaces and polymorphism for transports | `client.ServerLink` (`TlsLink`, `WebSocketLink`), `ClientController` |
| Adapter pattern for browsers | `server.WebGateway` (HTTP files, `/health`, `/media`, WebSocket framing) |
| Stream compression with Deflater/Inflater | `WebGateway.WsOutput`, `WebGateway.WsInput` |
| Strategy pattern for storage replicas | `StorageReplica`, `BlobStore`, `BlobStoreReplica`, `RootzBlobStore` |
| AES-256-GCM encryption and GZIP | `BlobStoreReplica.seal` / `open` |
| Scheduled executor and batching thread | `BlobStoreReplica` uploader |
| REST over the JDK HTTP client, exception translation | `RootzBlobStore` |
| Recursive-descent parsing | `common.util.MiniJson` |
| Functional interfaces and lambdas | `BlobStoreReplica.RemoteCall`, `WebGateway.MediaSource` |
| Records | `WebGateway.Upgrade`, `WebGateway.Asset` |
| Concurrent maps and atomic compute for multi-device presence | `EmberServer.online`, `sendToUser`, `removeOnline` |
| Integration check against a live service | `edu.ember.tools.ReplicaCheck` |
| Elliptic-curve key agreement, HKDF, AES-GCM, ES256 signatures (Web Push) | `server.WebPush` |
| Change tracking with revisions and deletion markers | `InMemoryStorageEngine.getChangesSince`, `StorageEngine.currentRevision` |
| Enum with constructor fields and `toString()` | `ThemeManager.Theme` (six themes, settings dropdown) |
