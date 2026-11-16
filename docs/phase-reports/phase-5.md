# PHASE 5 REPORT

Calendar: planned Fri 20–Sun 22 Nov 2026; status ahead by 4 calendar days (current date Mon 16 Nov 2026); scope changes: none.

Files created/changed: `client/ui/ThemeManager.java`, `FontResolver.java`, `RoundedPanel.java`, `AvatarComponent.java`, `BadgeLabel.java`, `ChatBubblePanel.java`, `ToastBanner.java`, `NotificationService.java`, `AuthPanel.java`, `ChatListPanel.java`, `ConversationPanel.java`, `SettingsPanel.java`, `ChatWorkspacePanel.java`; updated `EmberClientApp`, `ClientController`, `ClientStateModel`, test suite, `scripts/verify-ui.bat`, `scripts/verify-ui.sh`; generated four screenshots under `docs/screenshots/`; updated README, architecture, concept map, viva notes, report implementation draft, and progress.

## How to build and run

Windows:

```text
scripts\build.bat
scripts\run-tests.bat
scripts\verify-ui.bat
scripts\run-server.bat --port 7443 --storage server_data --durability strict
scripts\run-client.bat --host 127.0.0.1 --port 7443 --profile alice
```

Linux/macOS uses the matching `.sh` scripts. The `.sh` build, test, and UI verifier have not been run on those platforms and remain HUMAN-VERIFY.

## Gate checks

Command: `scripts\build.bat`

Raw output:

```text
Build passed.
```

Result: PASS with the required Java 17 API, UTF-8 encoding, and strict lint flags.

Command: `scripts\run-tests.bat` (run with local loopback socket permission)

Raw output:

```text
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
Ember tests: 19 passed, 0 failed.
```

Result: PASS. The suite includes model-listener EDT checks, theme token checks, word-wrapped Arabic/emoji message bodies, the Phase 4 live TLS flows, WAL recovery, authorization negatives, and group receipt aggregation.

Command: `scripts\verify-ui.bat` (run with local loopback socket permission)

Raw output:

```text
Build passed.
Captured docs\screenshots\phase-5-dark-1280x720.png 1280x720
Captured docs\screenshots\phase-5-dark-1920x1080.png 1920x1080
Captured docs\screenshots\phase-5-light-1280x720.png 1280x720
Captured docs\screenshots\phase-5-light-1920x1080.png 1920x1080
Phase 5 visual checks passed
```

Result: PASS. I inspected all four offscreen captures. The chat list and wrapped message bodies remain visible at both target sizes in both themes. Vertical text bounds are checked against the text area's preferred height. Arabic and common emoji are present; exact glyph quality still needs a human check on the demo machine.

## File and test status

TESTED (compiled): all Java sources under `src/`, including UI panels and verifier. TESTED (executed): all 19 tests and the offscreen visual verifier. `scripts/build.bat`, `scripts/run-tests.bat`, and `scripts/verify-ui.bat` ran.

UNTESTED: Linux/macOS `.sh` scripts; desktop interaction by a human; real system-tray delivery and fallback toast; physically simulating a network loss from the settings control; screen-reader behavior. These are HUMAN-VERIFY and do not block this phase.

## Viva pack

1. **Why use a `JTextArea` for each message bubble instead of an HTML label?** `JTextArea` wraps plain text and avoids the HTML-label emoji wrapping issue on scaled displays. It keeps hostile text literal rather than interpreting markup.
2. **How does theme switching update the interface?** `ThemeManager` changes shared palette tokens, then each workspace panel applies those tokens to its controls and repaints dynamic components. Keeping colors centralized makes dark/light behavior consistent.
3. **How are notifications shown when the window is not focused?** `NotificationService` uses the desktop tray when supported. If the platform has no tray or refuses the tray icon, `ToastBanner` presents the message inside the app.

These answers are appended to `docs/VIVA_NOTES.md`.

## Owner review

- R1: review the UI-to-controller message path and verify no socket I/O is performed by a Swing action.
- R2: trace model listener updates, typing and read receipt behavior, reconnect banner, and the network-drop control.
- R3: review the existing TLS trust configuration and explain why notification code contains no credentials.
- R4: run `scripts\verify-ui.bat`, open the app at both target resolutions, toggle both themes, check Arabic/emoji wrapping, keyboard shortcuts, resizing, and settings.
- R5: verify placeholder navigation is limited to implemented functionality; own the Campus Pulse UI in Phase 6.
- R6: review all four screenshots, test on Linux/macOS when available, and keep the `.sh` scripts and tray behavior in HUMAN-VERIFY until then.

Known gaps: the chat UI has not been manually exercised in a visible desktop session. The optional tray notification was not tested on the demo machine. Campus Pulse screens, group info drawer, admin screen, 24-hour stories, and the TTL composer control are future phases; no nonfunctional controls were added for them. Read receipt toggling is local to the client.

Next phase: Phase 6 — Campus Pulse core.
