# Implementation

## Modules

Phase 0 provides the product constants, named logging helper, build scripts, and zero-dependency test runner. Phase 1 adds the binary protocol (`WireFrame`, `FrameCodec`, `FrameFactory`, `KvPayloadCodec`), TLS socket creation (`TlsSocketFactory`), PBKDF2 password hashing, input validation, and checked application exceptions.

Phase 2 adds an in-memory storage implementation, account registration and login, connection-bound authenticated identity, server-assigned message ordering, direct message fan-out, sender ACK, recipient DELIVERED receipt, a Swing login/chat shell, and dedicated client sender/receiver threads.

Phase 3 adds `WalStorageEngine` with CRC32 verification and torn-tail truncation, `AutoSaveThread`, `SecurityAuditLogger`, username lockout, per-user message token buckets, loopback-aware IP throttling, and a semaphore limiting concurrent password hash work to four operations.

Phase 4 extends messaging with client-generated idempotency keys, server-assigned conversation/global sequences, a framed local outbox, token-based resume and sync, authorized history pulls, heartbeat, presence snapshots and updates, rate-limited typing events, group roles/fan-out, and aggregated delivered/read receipts. The client retries connections with bounded exponential backoff and jitter. The test suite exercises offline delivery after session resume, one-copy duplicate resends, WAL recovery of an undelivered message, four-member group receipts, and negative authorization cases for messages, receipts, history, typing, and group membership changes.

Phase 5 adds Ember Night and Campus Daylight theme tokens, a resolved font fallback, reusable rounded panels, initials avatars, wrapped text bubbles, message receipt marks, a searchable conversation list, an authentication panel, a conversation composer, settings, timed toasts, and system-tray notification fallback. The client includes Ctrl+D/Ctrl+F shortcuts, a reconnect banner, and a developer network-drop control. Offscreen screenshots are generated at 1280×720 and 1920×1080 in both themes; the visual verifier checks message wrapping and non-empty screenshot output.

Phase 6 adds public user profiles, follow/unfollow, safe PNG/JPEG upload, UUID-named media storage, 300-pixel thumbnails, feed posts, likes, comments, and the Campus Pulse, profile, post-card, and post-detail views. `ImageValidator` checks file size, extension, and signature, reads width and height through `ImageReader` before decode, rejects dimensions above 4096 or 16 million pixels, then re-encodes a fresh RGB JPEG. Upload frames use a separate bounded limit and allow large payloads only for `CMD_MEDIA_UPLOAD`; the client reads files and prepares Base64 outside the EDT. Social metadata is persisted as WAL records. Profile updates are self-only, and unpublished or missing media/post objects return 404.

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

## Security

Use only the claims recorded in `docs/SECURITY.md` and the project claims table. Do not describe planned features as completed.

## Screenshots

Shot list: login/register, chat list, conversation, group conversation, Campus Pulse feed, post detail, profile, and settings.  
Phase 5 captures: `docs/screenshots/phase-5-dark-1280x720.png`, `phase-5-dark-1920x1080.png`, `phase-5-light-1280x720.png`, `phase-5-light-1920x1080.png`. Remaining shot list: login/register, Campus Pulse feed, post detail, profile, and settings walkthrough.
