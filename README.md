# Ember

**Say it. Spark it. Let it fade.**

Ember is a Java desktop and web messenger and campus social feed.

The current build provides TLS 1.3 transport, a bounded wire protocol, registration/login, a themed Swing chat workspace, live 1:1 and group messaging, receipts, offline resume, private profiles with follow approval, block/report, disappearing messages, replies, reactions, edit/delete, pins/stars/search, 24-hour stories, group polls, and message forwarding. Campus Pulse supports validated image posts, likes, comments, and profile/story views. The server uses CRC-protected WAL storage with GZIP snapshot rotation, username lockout, rate limits, and audit events.

## Requirements

- JDK 21 toolchain (compiled with `--release 17`)
- Windows scripts are provided in `.bat` form; matching `.sh` scripts are also included.

## Build and run

```text
scripts/build.bat
scripts/run.bat
scripts/run-tests.bat
scripts/run-server.bat --port 7443 --storage server_data --durability strict
scripts/run-client.bat --host 127.0.0.1 --port 7443 --profile alice
scripts/run-demo.bat
scripts/package-demo.bat
java -cp build\classes edu.ember.tools.AttackSimulator
java -cp build\classes edu.ember.tools.ConcurrencyStressHarness
```

On Unix-like systems, use the matching `scripts/build.sh`, `scripts/run.sh`, `scripts/run-tests.sh`, `scripts/run-server.sh`, and `scripts/run-client.sh` scripts.

## Web app and multiple devices

All messages live on the server, so an account can sign in on any number of machines at once (desktop app, browser, phone) and every device sees the full history plus live messages, including ones the same account sent from another device.

- **Server address.** The server now listens on all network interfaces (`0.0.0.0`). Use `--bind 127.0.0.1` to keep it local-only.
- **Web app.** The same port serves the web app: open `https://<server-host>:7443/`. It speaks the same binary protocol over a WebSocket, so every server rule applies unchanged. The page is about 30 KB, sent gzipped and revalidated by ETag, and a service worker caches it. Browsers can install it as an app ("Install app" / "Add to Home screen").
- **Certificates.** `gen-keys` creates a self-signed certificate valid for `localhost` and `127.0.0.1` only. To reach the server from other machines, delete `config/ember-server.p12`, set the extra names, and regenerate, for example `set EMBER_HOSTS=ip:192.168.1.20,dns:mypc` then `scripts\gen-keys.bat`. Then copy the new `config/ember-client-trust.p12` to each desktop-client machine. Browsers show a warning for a self-signed certificate, which the user must accept once.
- **Public website.** For a browser-trusted certificate, run a TLS reverse proxy (Caddy, nginx) in front of the web listener: `scripts/run-server.bat --http-port 8080`. This plain-HTTP listener binds to `127.0.0.1` only and serves the web app alone (no desktop frames), for example with Caddy: `chat.example.com { reverse_proxy 127.0.0.1:8080 }`. Behind a proxy, all clients appear to come from loopback, so the per-IP login limiter does not apply, but per-account lockout still does.

**Limits.** Message length and image file size are unlimited. Kept on purpose: frames before login are capped at 4 KB, images over 4096 px per side or 16 MP are rejected (they decode to huge in-memory bitmaps), and the per-user flood limiter still applies.

## Local demo

Run `scripts\run-demo.bat` (Windows) or `scripts/run-demo.sh` (Linux/macOS) to build and start a server plus three clients on `localhost:7443`. If keys are missing, the script runs `gen-keys`; `--demo-seed` adds Alice, Bob, Charlie, a study group, a poll, and sample Campus Pulse content to an empty demo data directory. Seeded credentials are `alice` / `alice-demo-42`, `bob` / `bob-demo-42`, and `charlie` / `charlie-demo-42`. Alice receives the administrator role only in this demo launch, which explicitly passes `--admin-user alice`. The seed skips when Alice already exists and does not reset server data.

## Proof tools and packaging

`edu.ember.tools.AttackSimulator` opens real local TLS connections and demonstrates protocol rejection, five-failure account lockout, username allowlist validation, literal hostile text, connection-bound identity, duplicate-resend idempotency, object authorization, renamed-script upload rejection, and oversized-PNG header rejection. `ConcurrencyStressHarness` runs twice by default: each run starts 10 headless TLS clients and sends 500 messages, checking loss, duplicate IDs, and sequence order. Both use ephemeral ports and close their clients and server when finished.

`scripts/package-demo.bat` or `scripts/package-demo.sh` creates runnable `build/jars/ember-client.jar` and `build/jars/ember-server.jar`, plus Windows/Linux client and server app-images under `build/phase8/package`. The exact Windows app-image invocation is:

```text
jpackage --type app-image --input build\jars --dest build\phase8\package --name EmberClient --main-jar ember-client.jar --main-class edu.ember.client.EmberClientApp
```

`jpackage --type app-image` bundles a Java runtime and does not need WiX on Windows. Building the app-image still requires JDK 21 and `jpackage`. If `jpackage` is unavailable, use the runnable JAR with `java -jar build/jars/ember-client.jar` or `java -jar build/jars/ember-server.jar` on a machine with a Java runtime. Runtime key material remains local under the ignored `config/` directory; do not distribute private keystores or `secrets.properties`.

## TLS development keys

The transport uses a local server keystore and client truststore. Run `scripts/gen-keys.bat` (or `scripts/gen-keys.sh`) once. The script reads `EMBER_KEYSTORE_PASS` from the environment, then from the git-ignored `config/secrets.properties`; if neither is present, it creates a random password there. On Windows, `setx EMBER_KEYSTORE_PASS value` only affects new terminals, so open a new terminal before running the script. The server keystore, client truststore, and secrets file are local-only and git-ignored; `gen-keys` creates a matching certificate/truststore pair for each local keystore. Do not reuse the generated password elsewhere.

## Security claims

Implemented claims: “Traffic between client and server is encrypted with TLS 1.3.” “Passwords are stored with salted PBKDF2-HMAC-SHA256 at 600,000 iterations, the OWASP work factor for that algorithm. OWASP's first choice is Argon2id, which the JDK does not include.” “The wire protocol is length-prefixed with size limits and does not use Java serialization.” “Every command that names an object is authorization-checked on the server, with a negative test per rule.” “Uploaded images are validated by signature and size, then re-encoded.” Private profiles, follow approval, block checks, reports, stories, and polls are authorized on the server. Configure administrators explicitly with `--admin-user username`; no admin username is enabled by default.
