# PHASE 0 REPORT

Calendar: planned Sun 15–Mon 16 Nov 2026; status on time; scope changes: none.

## Files present

- `.gitignore`, `README.md`
- `src/main/java/edu/ember/EmberScaffold.java`
- `src/main/java/edu/ember/common/util/AppInfo.java`, `AppLog.java`
- `src/test/java/edu/ember/test/EmberTestSuite.java`
- `scripts/build.bat`, `build.sh`, `run.bat`, `run.sh`, `run-tests.bat`, `run-tests.sh`, `gen-keys.bat`, `gen-keys.sh`
- `docs/ARCHITECTURE.md`, `CONCEPT_MAP.md`, `DEMO_SCRIPT.md`, `OWNERSHIP.md`, `PROTOCOL.md`, `SECURITY.md`, `VIVA_NOTES.md`
- `docs/uml/README.md`
- `docs/report/01-cover.md` through `07-references.md`
- `docs/PROGRESS.md`, `docs/phase-reports/phase-0.md`

## Phase 0 gate checks

Command: `& .\scripts\run-tests.bat`

Raw output:

```text
Build passed.
Ember tests: 0 passed, 0 failed.
```

Result: PASS. The runner currently contains zero feature tests.

Command: `& .\scripts\run.bat`

Raw output:

```text
Build passed.
Ember | Say it. Spark it. Let it fade.
Phase 0 scaffold is ready.
```

Result: PASS.

Compiler: `javac 21.0.13`, using `--release 17 -encoding UTF-8 -Xlint:all,-serial -Werror`.

## How to build and run

Windows PowerShell or Command Prompt:

```text
scripts\build.bat
scripts\run.bat
scripts\run-tests.bat
```

Linux/macOS shell:

```text
./scripts/build.sh
./scripts/run.sh
./scripts/run-tests.sh
```

The shell scripts are provided but were not run on Linux/macOS in this phase. The `run` command currently launches the Phase 0 scaffold banner; server and desktop client launchers arrive in later phases.

## Frozen interface list

- `StorageEngine`: `init`, `saveUser`, `findUser`, `appendMessage`, `updateMessageStatus`, `findBySenderAndClientMsgId`, `getDeltaFramesSince`, `getHistory`, `purgeExpiredRecords`, `close`.
- `PasswordHasher`: `hash`, `verify`, `dummyHash`.
- `ClientModelListener`: client model change notification.
- `FrameCodec`: `readFrame`, `writeFrame`.
- `ClientController`: `login`, `register`, `sendMessage`, `openConversation`, `markRead`, `setTyping`, `logout`, `addListener`, `removeListener`.

Responsibilities are frozen; full Java signatures are added when each interface is implemented.

## Viva pack

1. **Why centralize the app name and tagline?** Both values appear in multiple parts of the product. `AppInfo` gives the UI and logs one source of truth and makes a rename straightforward.
2. **Why compile with plain `javac` instead of Maven or Gradle?** The project is constrained to JDK APIs and should build offline on the demo laptop. Direct compiler flags also make the Java version and warnings policy visible to the team.
3. **Why use a zero-dependency test runner?** It runs with the JDK alone and keeps the demonstration setup small. Feature tests can be registered in the runner as the implementation grows.

These answers are also present in `docs/VIVA_NOTES.md` under Phase 0.

## Owner review

- R1 reads the frozen opcode table in `docs/PROTOCOL.md` and the frame limits; explain the fixed header and opcode ranges.
- R2 reads the client package plan and interface list in `docs/ARCHITECTURE.md`; explain how views will observe the client model.
- R3 reads the security claims and authorization matrix in `docs/SECURITY.md`; distinguish planned controls from implemented behavior.
- R4 reads the Swing EDT and UI constraints in `docs/ARCHITECTURE.md`; explain why network work cannot run on the EDT.
- R5 reads the Campus Pulse scope and image upload rules in `docs/SECURITY.md`; explain the planned signature, size, and dimension checks.
- R6 reads the build scripts, test runner, report kit, and `docs/OWNERSHIP.md`; run the build and test scripts and check the report placeholders.

Known gaps: Phase 0 contains no transport, UI, server, storage, or feature tests. `.sh` and key-generation scripts are UNTESTED. The workspace had no Git repository before this run; it is initialized and checkpointed now.

Next phase: Phase 1 — Transport.
