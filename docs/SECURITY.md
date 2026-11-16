# Security design

## Implementation status after Phase 6

Phases 1–6 implement TLS 1.3 loopback sockets, bounded length-prefixed frames, PBKDF2-HMAC-SHA256 password hashing, account registration/login, WAL persistence with CRC and torn-tail recovery, username lockout, per-user message throttling, structured audit events, core messaging/groups, and Campus Pulse. Social profiles and posts are public in this phase. Profile updates are self-only; follows require an existing target; post interactions require an existing post; media reads require a media record linked to a published post. Private-account filtering and block rules are not implemented yet.

## Claims and implementation status

TLS 1.3 sockets, frame size checks, no Java serialization, PBKDF2-HMAC-SHA256 at 600,000 iterations, username/message validation, server-side object checks for implemented commands, durable audit logging, rate limits, and signature/size-validated image re-encoding are implemented and tested. Phase 6 feeds and profiles are public; private accounts and blocking remain future work.

- Traffic between client and server is encrypted with TLS 1.3. Clients trust only Ember's generated truststore and verify the server name.
- Passwords are stored with salted PBKDF2-HMAC-SHA256 at 600,000 iterations, the OWASP work factor for that algorithm. OWASP's first choice is Argon2id, which the JDK does not include.
- The wire protocol is length-prefixed with size limits and does not use Java serialization.
- Every command that names an object is authorization-checked on the server, with a negative test per rule.
- Uploaded images are validated by signature and size, then re-encoded.

## Required object authorization matrix

| Command | Who may do it | Violation |
|---|---|---|
| `CMD_MSG_SEND` | DM participant (not blocked) or group member | 403 |
| `CMD_MSG_RECEIPT` | Conversation member other than sender | 403 and audit |
| `CMD_MSG_REACT`, `CMD_MSG_STAR` | Member of containing conversation | 403 |
| `CMD_MSG_EDIT` | Message sender | 403 and audit |
| `CMD_MSG_DELETE` | Sender or group moderator for group message | 403 and audit |
| `CMD_MSG_PIN` | DM participant or group moderator | 403 |
| `CMD_POLL_VOTE` | Group member, once per poll | 403 or 409 |
| `CMD_GROUP_MEMBER` | Group moderator | 403 |
| `CMD_SYNC_PULL`, `CMD_HISTORY_PULL` | Conversation member | 403 |
| `CMD_PROFILE_UPDATE` | The authenticated user's own profile | 403 and audit |
| `CMD_FOLLOW_ACTION` | Authenticated user; target profile must exist; self-follow is invalid | 400/404 |
| `CMD_MEDIA_UPLOAD` | Authenticated owner; size/signature/dimensions/extension must pass validation | 400 and `upload_rejected` audit |
| `CMD_POST_CREATE` | Authenticated owner of the referenced media | 403; missing media returns 404 |
| `CMD_MEDIA_GET` | Authenticated user, only for media linked to a published public post | 404 |
| `CMD_POST_INTERACT` | Authenticated user, only for an existing public post | 404 |
| `CMD_PROFILE_GET`, `CMD_FEED_PULL` | Authenticated user; target profile must exist when specified | 404 |
| `CMD_STORY_VIEW` | Owner, or permitted followers when private, and not blocked | 404 (Phase 7 scope) |
| `CMD_ADMIN_ACTION` | Admin | 403 and audit |
| `CMD_MOD_REPORT` | Authenticated user, rate limited | 429 |

## Threat model

| Asset | Threat | Planned mitigation |
|---|---|---|
| Credentials | Sniffing or offline cracking | TLS 1.3 and salted PBKDF2 600k |
| Messages and posts | Forged identity, flooding, privilege escalation | Connection-bound identity, server RBAC, token buckets |
| Other users' objects | ID guessing and unauthorized access | Requester-scoped lookups and audited authorization failures |
| Server process | Crafted frames, deserialization gadgets, image bombs, path traversal | Frame limits, no Java serialization, header-first image checks, UUID file names |
| Logs | Log forging | Structured JSON and escaped control characters |
| Private chats | Disclosure from server files | Optional E2EE is future COULD work; otherwise private messages are not protected from the server |

Per-username lockout can be abused to lock out another user; admin unlock is the mitigation. Per-IP throttling defaults off on loopback because demo clients share 127.0.0.1. Never claim the project is unhackable, more secure than WhatsApp or Instagram, or immune to all exploits.
