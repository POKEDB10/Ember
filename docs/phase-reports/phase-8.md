# PHASE 8 REPORT — Proof, packaging and polish

**Calendar:** planned Thu 26 Nov 2026; completed Mon 16 Nov 2026, 10 calendar days ahead. Scope changes: Phase 8 now ignores and untracks the generated client truststore to comply with the no-keystore-tracked hygiene gate; `gen-keys` recreates a matching local truststore. Phase 9 remains off.

**Result:** PASS.

## Files created or changed

- Added `src/main/java/edu/ember/tools/AttackSimulator.java`, `ConcurrencyStressHarness.java`, and `DemoSeeder.java`.
- Added the server `--demo-seed` option and a test that checks fixture persistence and idempotency.
- Added `scripts/run-demo.bat`, `scripts/run-demo.sh`, `scripts/package-demo.bat`, and `scripts/package-demo.sh`.
- Updated `scripts/gen-keys.bat` and `.sh` to refresh a stale client truststore through an intermediate file under ignored `build/`.
- Added five PlantUML diagrams and `docs/uml/README.md`.
- Updated `README.md`, `docs/DEMO_SCRIPT.md`, `docs/PROGRESS.md`, `docs/ARCHITECTURE.md`, `docs/CONCEPT_MAP.md`, `docs/VIVA_NOTES.md`, and report drafts 04–06.
- Added `/config/ember-client-trust.p12` to `.gitignore` and removed that generated file from the Git index. The local truststore remains available for this checkout.

## Build and run

- Windows: `scripts\build.bat`, `scripts\run-tests.bat`, `scripts\run-demo.bat`, `scripts\package-demo.bat`.
- Linux/macOS: `scripts/build.sh`, `scripts/run-tests.sh`, `scripts/run-demo.sh`, `scripts/package-demo.sh`.
- Demo logins: `alice` / `alice-demo-42`, `bob` / `bob-demo-42`, `charlie` / `charlie-demo-42`.
- Runnable JARs: `java -jar build/jars/ember-client.jar` and `java -jar build/jars/ember-server.jar`; local key configuration is still required.
- Windows app-image command used by the packaging script: `jpackage --type app-image --input build\jars --dest build\phase8\package --name EmberClient --main-jar ember-client.jar --main-class edu.ember.client.EmberClientApp`. The script builds the matching EmberServer image as well. `app-image` bundles a runtime and does not need WiX; building requires JDK 21 and `jpackage`.

## Gate checks

### Strict build — PASS

Command: `scripts\build.bat`

Actual output:

```text
Build passed.
```

### Complete test suite — PASS, 30 passed, 0 failed

Command: `scripts\run-tests.bat` (with local TLS socket permission)

Actual output:

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
PASS: demo seed persists sample accounts and content idempotently
Ember tests: 30 passed, 0 failed.
```

The first sandbox-only attempt was denied local `connect` permissions; the exact suite command above was rerun with local socket permission and passed. No test was skipped or weakened.

### Attack simulator — PASS; all scenarios handled

Command: `java -cp build\classes edu.ember.tools.AttackSimulator` (local TLS permission)

Actual output:

```text
HANDLED: invalid frame magic closed the TLS connection
HANDLED: pre-auth oversized frame was rejected before its payload
HANDLED: username allowlist rejected CR/LF input with 400
HANDLED: five bad logins locked the account, including its correct password
HANDLED: hostile text stayed literal; forged sender identity was ignored
HANDLED: duplicate resend remained idempotent
HANDLED: cross-user message edit received 403
HANDLED: renamed shell upload was rejected by image signature checks
HANDLED: 12,000 x 12,000 PNG header was rejected before pixel decode
```

### Concurrency stress — PASS twice

Command: `java -cp build\classes edu.ember.tools.ConcurrencyStressHarness` (local TLS permission)

Actual output:

```text
Run 1 PASS: 10 TLS clients, 500 messages, no loss, duplicates, or ordering errors.
Run 2 PASS: 10 TLS clients, 500 messages, no loss, duplicates, or ordering errors.
```

Both runs used ephemeral-port servers and closed all clients/server in `finally`.

### Demo seed — PASS

Covered by the complete suite test `demo seed persists sample accounts and content idempotently`. It validates the three users, three-member group, post, media metadata/file, poll, and story; the second seed call returns without duplicating data.

### Runnable JARs — PASS

Command: `jar --describe-module --file build\jars\ember-client.jar; jar --describe-module --file build\jars\ember-server.jar`

Actual output (both report the requested main class):

```text
No module descriptor found. Derived automatic module.

ember.client automatic
requires java.base mandated
contains edu.ember
contains edu.ember.client
contains edu.ember.client.ui
contains edu.ember.common.exceptions
contains edu.ember.common.model
contains edu.ember.common.protocol
contains edu.ember.common.security
contains edu.ember.common.util
contains edu.ember.server
contains edu.ember.server.storage
contains edu.ember.tools
main-class edu.ember.client.EmberClientApp

No module descriptor found. Derived automatic module.

ember.server automatic
requires java.base mandated
contains edu.ember
contains edu.ember.client
contains edu.ember.client.ui
contains edu.ember.common.exceptions
contains edu.ember.common.model
contains edu.ember.common.protocol
contains edu.ember.common.security
contains edu.ember.common.util
contains edu.ember.server
contains edu.ember.server.storage
contains edu.ember.tools
main-class edu.ember.server.EmberServer
```

### App-image package — PASS

Command: `scripts\package-demo.bat` (filesystem escalation; output is confined to `build/phase8/package`)

Actual output:

```text
Build passed.
Created runnable JARs and app-images under build\phase8\package. App-images bundle a runtime; app-image does not require WiX.
```

An initial sandbox-only invocation reported the destination as not writable because Java `Files.isWritable` returned false for the workspace build directory. Re-running the same command with the granted filesystem escalation succeeded.

### Packaged client without JDK on `PATH` — PASS on this Windows host

Command:

```powershell
$savedPath = $env:PATH; $env:PATH = 'C:\Windows\System32'; $app = Start-Process -FilePath (Resolve-Path 'build\phase8\package\EmberClient\EmberClient.exe') -WorkingDirectory (Get-Location).Path -PassThru -WindowStyle Hidden; Start-Sleep -Seconds 8; $stillRunning = -not $app.HasExited; if ($stillRunning) { Stop-Process -Id $app.Id -Force }; $env:PATH = $savedPath; if ($stillRunning) { 'PASS: packaged EmberClient stayed running for 8 seconds with PATH=C:\Windows\System32 (no JDK on PATH)' } else { 'FAIL: packaged EmberClient exited with code ' + $app.ExitCode; exit 1 }
```

Actual output:

```text
PASS: packaged EmberClient stayed running for 8 seconds with PATH=C:\Windows\System32 (no JDK on PATH)
```

### Clean-unzip demo launcher — PASS on this Windows host

Command:

```powershell
$base = (Resolve-Path 'build\phase8').Path
$demoRoot = Join-Path $base 'clean-unzip-3'
if (Test-Path -LiteralPath $demoRoot) { throw "Clean-unzip target already exists: $demoRoot" }
New-Item -ItemType Directory -Path $demoRoot | Out-Null
Copy-Item -LiteralPath 'src','scripts','docs' -Destination $demoRoot -Recurse
Copy-Item -LiteralPath 'README.md','.gitignore' -Destination $demoRoot
New-Item -ItemType Directory -Path (Join-Path $demoRoot 'config') | Out-Null
Copy-Item -LiteralPath 'config\ember-client-trust.p12' -Destination (Join-Path $demoRoot 'config')
$existingIds = @(Get-Process java,javaw -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
$launched = @()
Push-Location $demoRoot
try {
    & .\scripts\run-demo.bat
    Start-Sleep -Seconds 8
    $launched = @(Get-Process java,javaw -ErrorAction SilentlyContinue | Where-Object { $existingIds -notcontains $_.Id })
    if ($launched.Count -lt 4) { throw "Expected server and three client JVMs; found $($launched.Count)" }
    $portOpen = Test-NetConnection -ComputerName 127.0.0.1 -Port 7443 -InformationLevel Quiet
    if (-not $portOpen) { throw 'Demo server did not listen on port 7443' }
    $walFiles = @(Get-ChildItem (Join-Path $demoRoot 'server_data') -Recurse -File -ErrorAction SilentlyContinue)
    if ($walFiles.Count -eq 0) { throw 'Demo seeding did not create server storage' }
    'PASS: clean-unzip run-demo generated keys, started one server and three client JVMs, listened on 7443, and created seeded storage.'
} finally {
    foreach ($process in $launched) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    Pop-Location
}
```

Actual output (the console harness prints the input-redirection notice when spawned UI processes inherit its redirected stdin; the listener, four JVMs, and seeded files were all verified):

```text
Build passed.
Generating 2,048 bit RSA key pair and self-signed certificate (SHA384withRSA) with a validity of 3,650 days
	for: CN=localhost, OU=Ember, O=Ember, L=Local, ST=Local, C=AE
Certificate stored in file <config\ember-server.cer>
Certificate was added to keystore
Client truststore refreshed from the generated server certificate.
ERROR: Input redirection is not supported, exiting the process immediately.
Demo server and three client windows launched. Demo passwords are in README.md.
PASS: clean-unzip run-demo generated keys, started one server and three client JVMs, listened on 7443, and created seeded storage.
```

The clean-unzip attempt exposed the pre-existing-truststore password mismatch. `gen-keys` now imports to an ignored intermediate truststore and replaces the generated truststore; the rerun passed. The direct `.bat` run from an ordinary Windows terminal remains a human check because this automated shell supplies redirected stdin.

### Repository hygiene — PASS

Commands: `git ls-files config`; `git ls-files | Select-String -Pattern '(^|/)(secrets\.properties|ember-server\.p12|ember-client-trust\.p12|server_data/|client_data/|logs/)|security-audit'`; `git check-ignore -v config/ember-client-trust.p12 config/ember-server.p12 config/secrets.properties server_data/wal/events.wal client_data/alice/session.token logs/security-audit.json`; `git diff --check`.

Actual output:

```text
config/ember-server.cer
.gitignore:3:/config/ember-client-trust.p12 config/ember-client-trust.p12
.gitignore:2:/config/ember-server.p12 config/ember-server.p12
.gitignore:4:/config/secrets.properties config/secrets.properties
.gitignore:5:/server_data/ server_data/wal/events.wal
.gitignore:6:/client_data/ client_data/alice/session.token
.gitignore:7:/logs/ logs/security-audit.json
```

The tracked-path filter and `git diff --check` produced no findings (Git emitted only line-ending normalization notices). No keystore, secrets, runtime data, or log files remain tracked. `config/ember-server.cer` is the public certificate, not a keystore.

## Documentation and viva pack

Updated `ARCHITECTURE.md`, `CONCEPT_MAP.md`, `VIVA_NOTES.md`, `README.md`, `DEMO_SCRIPT.md`, and report drafts 04–06. Added five PlantUML sources for use case, class, send/deliver/read sequence, message/connection state, and login/image upload activity. No new frozen interface was created in this phase.

Viva questions and model answers are appended under Phase 8 in `docs/VIVA_NOTES.md`:

1. How does the stress harness find message loss, duplication, and ordering errors?
2. Why does the attack tool use raw frames as well as the normal client controller?
3. What does the app-image runtime bundle provide, and what configuration does it still need?

## Owner review

- R1: read `EmberServer.main` and `ConnectionHandler`; trace demo flag parsing, connection-bound identity, and one authorization check.
- R2: read `ClientController`, `OutboundSenderThread`, and `ClientStateModel`; explain ACK/delivery/read updates.
- R3: read `DemoSeeder`, `WalStorageEngine`, and `gen-keys` scripts; verify local key files remain ignored.
- R4: rehearse the chat, group, theme, and notification flow; capture real UI screenshots at 1280×720 and 1920×1080.
- R5: read the social model/service and demo fixtures; rehearse the seeded post/story and one real image upload.
- R6: run the suite, both tools, package script, and demo; render the UML, assemble the report, and check Git hygiene.

## HUMAN-VERIFY and known gaps

- Run `.sh` scripts on Linux/macOS; Windows host verification is complete.
- Launch the clean-unzip `.bat` from a normal terminal and confirm all three visible client windows can sign in with the seeded passwords.
- Render all five `.puml` diagrams and compare them with the implementation.
- On a separate machine with no JDK, unpack and launch the app-image with the matching `config/ember-client-trust.p12`; the current host test hid the local JDK from `PATH` but is not a second machine.
- Human-check system-tray behavior and Campus Pulse/profile/post detail/story/admin/group-info interactions, plus the 3 MiB upload walkthrough.
- Phase 5 offscreen chat screenshots exist; new Phase 6/7 panels still need real human screenshots.

## Next phase

Phase 9 is off. The team should read its owned modules, answer the viva notes, render the UML, take real screenshots, assemble the report, and rehearse the seven-minute demo three times.
