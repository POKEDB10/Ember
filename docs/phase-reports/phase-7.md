# Phase 7 report — Signature features

**Calendar:** planned Tue 24–Thu 26 Nov 2026; completed Mon 16 Nov 2026, eight calendar days ahead of the planned start. No scope cuts.

**Result:** PASSED. All Phase 7 SHOULD items, snapshot compaction, and the Phase 7 forwarding deliverable passed their feature gates.

**Files changed:** Phase 7 work spans `src/main/java/edu/ember/` models, protocol, storage, server, client, and UI; `src/test/java/edu/ember/test/EmberTestSuite.java`; living docs, report drafts, README/security/protocol, and offscreen screenshots. See the phase commit for the complete file list.

## Gate checks

### Strict build

Command: `scripts\build.bat`

Raw output:

```text
Build passed.
```

Result: PASS. This build uses `javac --release 17 -encoding UTF-8 -Xlint:all,-serial -Werror`.

### Full test suite

Command: `scripts\run-tests.bat`

Raw output:

```text
Build passed.
PASS: KV codec round trip
PASS: in-chat search matches every token including Arabic
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
PASS: compressed WAL snapshot compacts and restores later records
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
PASS: private profile requests, block enforcement, reports, and WAL recovery
PASS: story upload, viewer access, WAL recovery, and TTL expiry
PASS: administrator report queue and role authorization over TLS
PASS: 15-second messages expire on both live clients
PASS: replies, six quick reactions, and reaction authorization
Ember tests: 29 passed, 0 failed.
```

Result: PASS. Tests use ephemeral ports and close clients/servers in `finally` blocks. TLS was verified as TLS 1.3. The final run used loopback permission because the restricted sandbox denies local socket connections.

### Offscreen Swing verifier

Command: `scripts\verify-ui.bat`

Raw output:

```text
Build passed.
Captured docs\screenshots\phase-5-dark-1280x720.png 1280x720
Captured docs\screenshots\phase-5-dark-1920x1080.png 1920x1080
Captured docs\screenshots\phase-5-light-1280x720.png 1280x720
Captured docs\screenshots\phase-5-light-1920x1080.png 1920x1080
Phase 5 visual checks passed
```

Result: PASS for the verifier’s current chat surfaces and both target resolutions/themes. The screenshots are chat-only and the verifier identifies itself as Phase 5; new story/profile/admin/group-info screens still require a visible walkthrough.

### Repository hygiene

Commands: `git status --short --branch`; `git diff --check`; `git ls-files | Select-String -Pattern '(^|/)(config/.*\.p12|secrets\.properties|server_data/|client_data/|logs/)'`.

Result: PASS after removing report trailing spaces. `config/ember-client-trust.p12` is the only matching tracked file; it is the public client truststore documented as safe to commit. The private server keystore, `secrets.properties`, runtime data, and logs are ignored and untracked.

## Feature notes

- Expiring messages support off/15-second modes, WAL recovery, reaper removal, client removal, and countdown display.
- Replies, six quick reactions, sender-only edit, sender/moderator delete, pins, per-user stars, in-chat search, and independent message forwarding have live tests. Editing a forwarded copy leaves the source unchanged.
- Private profile/feed/story/media access requires owner access or an accepted follow and is hidden when either user has blocked the other. Follow requests can be approved/rejected. Blocks remove both follow directions and deny DM activity.
- Reports validate target type/ID and reason, are rate limited and audit logged, and survive WAL recovery. Admin access requires an explicit `--admin-user` setting; regular users receive 403.
- Stories use uploaded media and default to 24 hours; `--story-ttl-sec` supports short local demos. Group polls store a two-dimensional per-member matrix and broadcast updated totals.
- Snapshot compaction writes a checksummed GZIP snapshot via atomic replacement before WAL truncation. Restart tests cover the covered-prefix crash window, appended records, and repeated compaction.

## Living docs and report kit

Updated: `docs/ARCHITECTURE.md` (full `StorageEngine` signatures and Phase 7 architecture), `docs/CONCEPT_MAP.md`, `docs/VIVA_NOTES.md`, `docs/SECURITY.md`, `docs/PROTOCOL.md`, `README.md`, and report drafts 02–06.

## Viva pack

1. **How does a private follow become active?** The server first stores `PENDING`; private content remains hidden until the owner changes that relationship to `ACCEPTED`. The same visibility check protects profiles, feeds, media, and stories.
2. **How does snapshot rotation recover if the process stops before truncating the WAL?** The snapshot records the covered WAL prefix length and checksum. On restart, replay loads the snapshot and skips a matching prefix still present in the WAL, avoiding duplicate application.
3. **Why does forwarding construct a new message?** The forwarded copy receives a new ID, sender, destination, and timestamp, and does not share mutable message state. Edits to the copy therefore cannot alter the original.

## Owner review

- R1: trace `CMD_MSG_SEND`, `CMD_POLL_CREATE`/`CMD_POLL_VOTE`, `CMD_GROUP_INFO`, and server object authorization.
- R2: trace expiry/pin/star/poll events through `ClientController` and `ClientStateModel`; explain the forward-copy path.
- R3: inspect WAL types 14–20 and snapshot recovery; explain follow/block/report storage and admin-user configuration.
- R4: run chat UI, search, poll, group-info, admin, and story viewer; verify all Swing updates stay on the EDT.
- R5: trace private profile/story/media visibility and the follow approval/block behavior.
- R6: run the full suite, read this report and the Phase 7 viva pack, and verify repository hygiene.

## HUMAN-VERIFY

- Run `scripts/build.sh`, `scripts/run-tests.sh`, and `scripts/verify-ui.sh` on Linux/macOS.
- In a visible session, inspect Campus Pulse, profile privacy/request approval, post detail, story viewer, group info, poll, and admin queue at 1280×720 and 1920×1080 in dark and light themes.
- On the demo laptop, upload a real 3 MiB photo and confirm the UI stays responsive; verify tray/toast and disconnect/reconnect behavior.
- Install/run the packaged app-image on a machine with no JDK on `PATH` (Phase 8).

**Known gaps:** no Linux/macOS execution yet; visible social/SHOULD screen review and actual-photo upload remain pending. Follow approval currently uses account IDs entered in `ProfilePanel`; there is no username-to-ID lookup view. Read-receipt preference remains client-local.

**Next phase:** Phase 8 — proof, packaging, and polish. Phase 9 remains off.
