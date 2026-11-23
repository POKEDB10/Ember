# Ember

**Say it. Spark it. Let it fade.**

Ember is a Java desktop and web messenger and campus social feed.

The current build provides a website (installable as an app) and a Swing desktop app that share one server and work across any number of devices per account, TLS 1.3 transport, a bounded wire protocol, registration/login, a themed Swing chat workspace, live 1:1 and group messaging, receipts, offline resume, private profiles with follow approval, block/report, disappearing messages, replies, reactions, edit/delete, pins/stars/search, 24-hour stories, group polls, and message forwarding. Campus Pulse supports validated image posts, likes, comments, and profile/story views. The server uses CRC-protected WAL storage with GZIP snapshot rotation (optionally mirrored, encrypted, to rootz.so), username lockout, rate limits, and audit events.

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

## Website, app, and multiple devices

All messages live on the server, so an account can sign in on any number of devices at once (website, installed web app, Swing desktop app) and every device sees the full history plus live messages, including ones the same account sent from another device.

- **Website.** The server also serves a web client (plain HTML/CSS/JS, about 60 KB, gzipped, ETag-revalidated and cached by a service worker). It speaks the same binary protocol over a compressed WebSocket (`permessage-deflate`), so every server rule applies unchanged. Features: direct and group chat, replies, reactions, edit/delete, pins and stars (synced to every device), disappearing messages, a single Read more button on long messages, typing indicators, statuses (online, away, automatic idle, do not disturb, invisible), group polls, in-chat search, Campus Pulse photo posts with likes and comments, 24-hour stories, profiles with follow requests you can approve or decline, block and report, and seven retro themes in a picker. New messages in another chat pop up as a card with a quick-reply box; in a background tab they become system notifications with Reply and Mark read buttons; with no tab open they arrive as Web Push notifications (encrypted per browser, RFC 8291/8292). Do not disturb silences all of them. Chats are cached in the browser (IndexedDB) and a returning device downloads only the changes since its last visit; logging out deletes the cache. Photos are shrunk in the browser (max 1600 px JPEG) before upload, and images load over plain HTTP with a one-year private cache, so each one downloads once per device. Browsers can install it as an app ("Install app" / "Add to Home screen").
- **Voice and video calls (website).** One-to-one calls from any direct chat. They connect peer-to-peer when the network allows (lowest delay). Otherwise, within about 6 s, they fall back to a relay inside the site's own HTTPS WebSocket, so they still work where VoIP or UDP is blocked. Either way they are end-to-end encrypted: the browsers agree a key with ECDH P-256 and both screens show a safety code, so the server only forwards unreadable packets and never sees the other person's IP address. "Hide my IP in calls" (ME tab) always uses the relay. Data use: about 20–35 kbit/s for voice and about 200 kbit/s for 320×240 video.
- **Desktop app.** `scripts/run-client.bat --url https://ember.example.com` connects the Swing app to a hosted website over the same WebSocket (no local keys needed). Without `--url` it uses the direct TLS socket as before.
- **Server options.** `--bind` (TLS listener address, default `0.0.0.0`), `--no-tls` (web-only), `--http-port` / `--http-bind` (plain-HTTP web listener for an HTTPS proxy; default bind `127.0.0.1`), `--max-frame-mb` (largest message or upload after login, default 25), `--rootz` (off-server storage, below), `--new-storage-key` (prints a fresh encryption key), `--trust-proxy` (believe the proxy's `X-Forwarded-For`; use only when the HTTP listener is reachable solely through your own proxy).
- **Health check.** `GET /health` returns `ok` without touching data, for uptime monitors.
- **Self-hosting with TLS.** The TLS port also serves the website at `https://<host>:7443/`. `gen-keys` makes a self-signed certificate for `localhost`/`127.0.0.1`; add names with `EMBER_HOSTS=ip:192.168.1.20,dns:mypc` (delete `config/ember-server.p12` first) and copy the new `config/ember-client-trust.p12` to desktop-client machines.

**Limits.** Message length and upload size are capped only by `--max-frame-mb` (default 25 MB) so one upload cannot exhaust server memory. Frames before login are capped at 4 KB, images over 4096 px per side or 16 MP are rejected, and the per-user flood limiter still applies. With `--trust-proxy` the login limiter uses the client address from `X-Forwarded-For` (never a loopback address); without it, every proxied visitor counts as the proxy.

## Free hosting: Render + rootz.so

The server runs on Render's free plan, and all data is kept in your rootz.so account instead of on Render's disk (which is wiped on every restart). Everything sent to rootz is batched, GZIP-compressed and AES-256-GCM encrypted; rootz only ever holds unreadable files named `ember-*`.

How it works: each change is appended to the write-ahead log and uploaded in a batch every 2 seconds. After about 1 MB of changes, a compressed snapshot replaces the older log files. On startup (including every wake-up after Render's free plan sleeps) the server downloads the newest snapshot plus later logs and rebuilds its state. Images are uploaded once and fetched back only when someone views them. Idle memory use is about 40 MB.

1. **Make an encryption key** (keep it secret; without it the data cannot be read):
   ```text
   java -cp build\classes edu.ember.server.EmberServer --new-storage-key
   ```
2. **rootz.so:** create an API key in dashboard settings. Create a folder named `ember` with two subfolders, `data` (encrypted log and snapshots) and `media` (encrypted images), and note the ID of the `ember` folder (it appears in the dashboard address when the folder is open). Missing subfolders fall back to the `ember` folder itself.
3. **Render:** push this repository to GitHub, then in Render choose **New → Blueprint** and pick the repository. `render.yaml` creates one free Docker web service with a `/health` check. Fill in the secrets when asked:
   - `ROOTZ_API_KEY`: the rootz API key
   - `EMBER_STORAGE_KEY`: the key from step 1
   - `ROOTZ_FOLDER_ID`: the ID of the `ember` folder (leave empty to use the account root)
   - `EMBER_PUBLIC_URL`: your site address, e.g. `https://ember.pokedb.site` (push services use it as the contact)
   - `EMBER_ARGS`: extra options, for example `--admin-user yourname`, or leave empty
   - `EMBER_ICE_SERVERS`: optional JSON list of STUN/TURN servers for direct calls; leave empty to use Cloudflare and Google STUN
4. **Custom domain:** in the Render service, **Settings → Custom Domains → Add** `ember.pokedb.site`, then add the CNAME record Render shows at your DNS provider. Render issues the HTTPS certificate.
5. **Stay awake:** free Render services sleep after about 15 minutes without traffic. Create a free UptimeRobot HTTP monitor for `https://ember.pokedb.site/health` every 5 minutes. A sleeping service still wakes on the first visit (about 30–60 s), and no data is lost either way.

Run only one server against the same rootz files at a time. `java -cp build\classes edu.ember.tools.ReplicaCheck` checks a rootz key end to end with throwaway `embercheck-*` files and deletes them afterwards.

## Local demo

Run `scripts\run-demo.bat` (Windows) or `scripts/run-demo.sh` (Linux/macOS) to build and start a server plus three clients on `localhost:7443`. If keys are missing, the script runs `gen-keys`; `--demo-seed` adds Alice, Bob, Charlie, a study group, a poll, and sample Campus Pulse content to an empty demo data directory. Seeded credentials are `alice` / `alice-demo-42`, `bob` / `bob-demo-42`, and `charlie` / `charlie-demo-42`. Alice receives the administrator role only in this demo launch, which explicitly passes `--admin-user alice`. The seed skips when Alice already exists and does not reset server data.

## How the code works

`docs/CODEBASE_GUIDE.md` explains the whole codebase in plain words: folders, the frame protocol, the life of a message, calls, storage, security, and likely demo questions.

## Project report

`docs/report/Ember_Report.docx` (and `.pdf`) is the formatted course report: Times New Roman, 12 pt text, 14 pt headings, 1.15 line spacing, UML figures in `docs/report/figures/`, website and desktop screenshots in `docs/screenshots/`. Fill in the team names and roll numbers on the cover page before printing.

The report is generated, not hand-edited. In `docs/report/build/`: `java Diagrams.java ../figures` redraws the UML figures, `npm install` then `node report.js` writes the `.docx`, and LibreOffice's Python (`"C:\Program Files\LibreOffice\program\python.exe" export_pdf.py`) fills in the contents page numbers and writes the `.pdf`.

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
