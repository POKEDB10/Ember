# PHASE 6 REPORT

Calendar: planned Sun 22–Tue 24 Nov 2026; status ahead by 6 calendar days (current date Mon 16 Nov 2026); scope changes: none.

Files created/changed: model records UserProfile, MediaAsset, SocialPost, PostComment; ResourceNotFoundException; ImageValidator; SocialFeedService; extended StorageEngine, InMemoryStorageEngine, WalStorageEngine, AuthService, EmberServer, FrameCodec, ClientController, ClientStateModel, InboundReceiverThread, ChatWorkspacePanel, and EmberTestSuite; added CampusPulsePanel, ProfilePanel, PostCardPanel, and PostDetailDialog; updated README, SECURITY, ARCHITECTURE, CONCEPT_MAP, VIVA_NOTES, report drafts, and PROGRESS.

## How to build and run

Windows:
    scripts\build.bat
    scripts\run-tests.bat
    scripts\run-server.bat --port 7443 --storage server_data --durability strict
    scripts\run-client.bat --host 127.0.0.1 --port 7443 --profile alice

Linux/macOS use the matching .sh scripts; those scripts remain HUMAN-VERIFY.

## Gate checks

The first test attempt was blocked by the sandbox denying local socket connections. The same localhost-only gate was rerun with local socket permission.

Command: scripts\build.bat

Raw output:
    Build passed.

Result: PASS with strict compiler flags and Java 17 API target.

Command: scripts\run-tests.bat (localhost-only TLS loopback permission)

Raw output:
    Build passed.
    PASS: KV codec round trip
    PASS: bad frame magic rejected
    PASS: oversized frames rejected before payload read
    PASS: password hashing and dummy verification
    PASS: input validation
    Verified: default JVM trust store rejected Ember certificate
    TLS loopback negotiated: TLSv1.3
    PASS: TLS 1.3 loopback frame round trip
    PASS: headless registration, message, and delivery
    PASS: theme palettes, wrapped message bodies, and EDT notifications
    PASS: WAL restart recovers Arabic and emoji records and trims torn tail
    PASS: WAL CRC failure truncates invalid tail
    PASS: WAL restart restores group members and roles
    PASS: server restart restores accounts and messages from WAL
    PASS: five bad logins lock account and audit stays one JSON line
    PASS: concurrent password hashing is capped at four
    PASS: message flood receives 429
    PASS: loopback IP limiter remains disabled
    PASS: local outbox persists framed commands and reconnect delay is bounded
    PASS: resume syncs offline messages once and history, typing, presence, and ping work
    PASS: group fan-out aggregates receipts and denies unauthorized object access
    Verified: image signature and 12,000 x 12,000 PNG header rejected before decode
    PASS: image signatures and oversized dimensions are rejected before decode
    PASS: Campus Pulse WAL persists profiles, follows, posts, likes, and comments
    PASS: Campus Pulse TLS upload, feed, and object authorization
    Ember tests: 22 passed, 0 failed.

Result: PASS. The live TLS flow uploads a 3 MiB image while the EDT stays responsive, creates a post, fetches feed/media, and stores/reads an Arabic/emoji comment. Negative cases include a renamed script, cross-user profile update, unpublished media, foreign media used for post creation, missing follow/profile/feed/media/post objects, and like/comment attempts against missing posts. WAL restart covers profile, follow, media metadata, post, like, and comment.

Command: scripts\verify-ui.bat

Raw output:
    Build passed.
    Captured docs\screenshots\phase-5-dark-1280x720.png 1280x720
    Captured docs\screenshots\phase-5-dark-1920x1080.png 1920x1080
    Captured docs\screenshots\phase-5-light-1280x720.png 1280x720
    Captured docs\screenshots\phase-5-light-1920x1080.png 1920x1080
    Phase 5 visual checks passed

Result: PASS for existing chat screens after adding Campus Pulse navigation. Feed/profile/post-detail screenshots are not yet captured.

## Repository hygiene

Command: git check-ignore -v config/ember-server.p12 config/secrets.properties server_data client_data logs/ logs/test.log

Raw output:
    .gitignore:2:/config/ember-server.p12 config/ember-server.p12
    .gitignore:3:/config/secrets.properties config/secrets.properties
    .gitignore:4:/server_data/ server_data
    .gitignore:5:/client_data/ client_data
    .gitignore:6:/logs/ logs/
    .gitignore:6:/logs/ logs/test.log

Command: inspect git ls-files for private keystore, secrets.properties, server_data, client_data, and logs paths

Raw output:
    No private keystore, secrets, server_data, client_data, or logs path is tracked.

Command: git diff --check

Output contained only Git line-ending conversion notices (LF to CRLF); no whitespace errors were reported.

## File and test status

TESTED: all Java sources compile under --release 17 -encoding UTF-8 -Xlint:all,-serial -Werror. All 22 Ember tests and four screenshot captures passed. Oversized PNG headers are rejected before decode; uploaded names never become filesystem paths; generated UUID names are used. Client file reads, Base64 preparation, image decode, and scaling run in background workers.

UNTESTED / HUMAN-VERIFY: run .sh scripts on Linux/macOS; use Campus Pulse, profile, and post-detail screens in a visible session; upload an actual 3 MiB photo on the demo laptop and confirm responsiveness; inspect rendering at both resolutions; check tray/toast and disconnect/reconnect behavior.

## Viva pack

1. Why check image dimensions before decoding? ImageReader obtains dimensions from the header before allocating decoded pixels. Ember rejects oversized dimensions before read(0), then decodes and re-encodes accepted images.
2. Why replace uploaded filenames with UUIDs? The supplied name is used only to check the extension. The server generates disk names and never resolves a user-controlled path.
3. How does social object authorization work? Identity comes from the authenticated connection. Profile updates are self-only, media must be linked to a published post, and interactions require an existing post. Negative TLS tests check denial and hidden-object 404 behavior.

These questions and answers are appended to docs/VIVA_NOTES.md.

## Owner review

- R1: trace the social opcodes through EmberServer.ConnectionHandler and explain identity checks and the large upload frame exception.
- R2: trace profile/feed/media events through ClientController and EDT model notifications; verify image work stays off the EDT.
- R3: review ImageValidator, SocialFeedService, and social WAL replay.
- R4: inspect UI layout, themes, and wrapping.
- R5: trace profile/follow, upload/post, like/comment, feed/media flows and new authorization negatives.
- R6: verify raw gate output, screenshots, ignored paths, phase report, and Git hygiene.

Known gaps: profiles and posts are public; private-account filtering and block/report remain Phase 7. Feed size is capped at 10 and comment list at 20. Screenshot automation currently covers chat screens only. No original-file download or image editing is provided.

Next phase: Phase 7, beginning with disappearing messages (F14a), then replies/reactions, edit/delete, private accounts and block/report. Check the date before each SHOULD item.
