# PHASE 1 REPORT

Calendar: planned Sun 15–Mon 16 Nov 2026; status on time; scope changes: none.

Files created/changed: `scripts/gen-keys.bat`, `scripts/gen-keys.sh`, `README.md`, `docs/ARCHITECTURE.md`, `docs/CONCEPT_MAP.md`, `docs/SECURITY.md`, `docs/VIVA_NOTES.md`, `docs/report/02-abstract.md`, `docs/report/03-introduction.md`, `docs/report/05-implementation.md`, `docs/PROGRESS.md`, `docs/phase-reports/phase-0.md`; added `common/protocol` (`WireOpcodes`, `WireFrame`, `FrameCodec`, `FrameFactory`, `KvPayloadCodec`, `ProtocolFrameException`), `common/security` (`PasswordHasher`, `Pbkdf2PasswordHasher`, `TlsSocketFactory`, `InputValidator`), the `common.exceptions` hierarchy, and tests in `EmberTestSuite`.

## How to build and run

Windows:

```text
scripts\build.bat
scripts\run-tests.bat
```

Linux/macOS:

```text
./scripts/build.sh
./scripts/run-tests.sh
```

The `.sh` scripts are present but still require HUMAN-VERIFY on Linux/macOS. TLS keys can be generated with `scripts\gen-keys.bat` or `./scripts/gen-keys.sh`; the script reads `EMBER_KEYSTORE_PASS`, then the ignored `config/secrets.properties`, and generates a random local password if neither exists. On Windows, `setx` affects new terminals only.

## Gate checks

Command: `& .\scripts\build.bat`

Raw output:

```text
Build passed.
```

Result: PASS with JDK 21.0.13 and `--release 17 -encoding UTF-8 -Xlint:all,-serial -Werror`.

Command: `& .\scripts\run-tests.bat` (run with loopback socket permission)

Raw output:

```text
Build passed.
PASS: KV codec round trip
PASS: bad frame magic rejected
PASS: oversized frames rejected before payload read
PASS: password hashing and dummy verification
PASS: input validation
PASS: default JVM trust store rejected Ember certificate
TLS loopback negotiated: TLSv1.3
PASS: TLS 1.3 loopback frame round trip
Ember tests: 7 passed, 0 failed.
```

Result: PASS. The TLS exchange transferred and echoed a real 2 MiB frame over loopback. Frame limits were exercised at 4 KiB and 64 KiB, and the default JVM truststore was rejected.

Command: `& .\scripts\gen-keys.bat`

Raw output:

```text
Generating 2,048 bit RSA key pair and self-signed certificate (SHA384withRSA) with a validity of 3,650 days
    for: CN=localhost, OU=Ember, O=Ember, L=Local, ST=Local, C=AE
Certificate stored in file <config\ember-server.cer>
Certificate was added to keystore
```

Result: PASS.

Command: `$line=Get-Content 'config/secrets.properties'; $env:EMBER_KEYSTORE_PASS=$line.Substring($line.IndexOf('=')+1); keytool -list -v -alias ember-server -keystore config/ember-server.p12 -storepass $env:EMBER_KEYSTORE_PASS | Select-String -Pattern 'SubjectAlternativeName|DNSName|IPAddress' -Context 0,2`

Raw output:

```text
> SubjectAlternativeName [
>   DNSName: localhost
>   IPAddress: 127.0.0.1
  ]
```

Result: PASS; both SANs are present.

Command: `git check-ignore -v config/ember-server.p12 config/secrets.properties`

Raw output:

```text
.gitignore:2:/config/ember-server.p12    config/ember-server.p12
.gitignore:3:/config/secrets.properties  config/secrets.properties
```

Result: PASS. The generated certificate and client truststore are not ignored and may be committed for the demo.

Command: `rg -n "EMBER_KEYSTORE_PASS|setx" README.md`

Raw output:

```text
24:The transport uses a local server keystore and client truststore. Run `scripts/gen-keys.bat` (or `scripts/gen-keys.sh`) once. The script reads `EMBER_KEYSTORE_PASS` from the environment, then from the git-ignored `config/secrets.properties`; if neither is present, it creates a random password there. On Windows, `setx EMBER_KEYSTORE_PASS value` only affects new terminals, so open a new terminal before running the script. The server keystore and secrets file are local-only and git-ignored; the generated certificate and client truststore are safe to commit for the offline demo. Do not reuse the generated password elsewhere.
```

Result: PASS.

## File and test status

TESTED (compiled): all Java files under `src/main/java/edu/ember/common/exceptions/`, `common/protocol/`, and `common/security/`, plus `src/test/java/edu/ember/test/EmberTestSuite.java`. TESTED (executed): the six named tests in `EmberTestSuite` and its TLS truststore rejection assertion; the runner reports seven passed checks and zero failures. TESTED (executed): `scripts/build.bat`, `scripts/run-tests.bat`, and `scripts/gen-keys.bat`.

UNTESTED: `scripts/build.sh`, `scripts/run.sh`, `scripts/run-tests.sh`, and `scripts/gen-keys.sh` were not run on Linux/macOS. The report, README, architecture, security, concept map, viva notes, and report drafts are documentation and have not been machine-tested. The server keystore and `secrets.properties` are ignored; only the public certificate and client truststore are staged for the demo.

The initial sandboxed test attempt could not open loopback (`Permission denied: connect`). The final successful run used the granted local socket permission and exercised a real socket and TLS handshake.

## Viva pack

1. **Why does the frame codec check the length before allocating?** A frame header is controlled by the network peer. Validating its declared size first prevents an oversized allocation before the client or server can reject the frame.
2. **Why combine a private truststore with hostname checking?** The private truststore limits which certificate authorities the demo client accepts, and HTTPS endpoint identification checks that the certificate matches the requested host. Both protections are tested in the TLS loopback test.
3. **Why is a dummy PBKDF2 hash used for an unknown username?** It makes the unknown-user path perform the same kind of expensive hash verification as a known-user path. That reduces timing differences that could reveal whether an account exists.

These questions and answers are appended to `docs/VIVA_NOTES.md`.

## Owner review

- R1: read `common/protocol` and `docs/PROTOCOL.md`; trace frame header → length check → payload → KV decode.
- R2: read `docs/ARCHITECTURE.md` and the frozen client controller methods; explain why the TLS client sets endpoint identification.
- R3: read `common/security` and `docs/SECURITY.md`; explain the PBKDF2 encoding and dummy hash path.
- R4: review the EDT constraints in `docs/ARCHITECTURE.md`; Phase 1 has no Swing panels yet.
- R5: review `docs/SECURITY.md` image rules; image validation remains Phase 6.
- R6: run the build/tests and inspect test output and `.gitignore`; understand that the default JVM truststore test is an expected rejection.

Known gaps: authentication integration, session binding, persistent storage, Swing UI, group messaging, and image validation are not implemented yet. Tests cover the transport primitives but not a running Ember server/client because those arrive in Phase 2. The shell scripts remain HUMAN-VERIFY on Linux/macOS.

Next phase: Phase 2 — Walking skeleton.
