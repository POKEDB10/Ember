# Ember live demo — 7 minutes

**Say it. Spark it. Let it fade.** The demo is offline and uses one laptop: one TLS server and Alice, Bob, and Charlie clients on loopback. Start with `scripts\run-demo.bat` on Windows or `scripts/run-demo.sh` on Linux/macOS. The seed is idempotent: if Alice already exists, it leaves existing server data alone. Seeded login passwords: `alice-demo-42`, `bob-demo-42`, and `charlie-demo-42`.

| Time | Presenter action and talking point |
|---|---|
| 0:00–0:40 | Introduce Ember as a Java Swing messenger with Campus Pulse. Show the three windows and point out that TLS, storage, and all clients run locally. |
| 0:40–1:35 | Sign in as Alice and Bob. Send `مرحبا 🌋` in their direct conversation; show the delivered/read status. Explain that the server assigns message order and binds identity to each authenticated connection. |
| 1:35–2:25 | Open the seeded study group. Send a short message, reply, and quick reaction. Show the group poll and explain that only members can read or vote. |
| 2:25–3:15 | From Alice, edit a message and pin it. Use in-chat search for a word in the conversation. Mention that each change goes through a server-side authorization check. |
| 3:15–4:00 | Show the Campus Pulse post, its like/comment, Alice's profile, and the seeded story. If time allows, select a small JPEG and publish a new post. The server checks its signature and dimensions before decoding. |
| 4:00–4:45 | Open the admin console as Alice and show the report queue. Explain that Alice is an administrator only because the demo launch explicitly passes `--admin-user alice`; other server launches have no admin by default. |
| 4:45–5:35 | In a terminal run `java -cp build\classes edu.ember.tools.AttackSimulator`. Point out the `HANDLED` results for invalid framing, username validation, hostile text, forged sender identity, duplicate resend, and forbidden cross-user edit. This is security-by-design for a student prototype, not a comparison with commercial messengers. |
| 5:35–6:20 | Show `server_data\wal` and explain CRC-protected WAL recovery and snapshots. The regular test suite verifies restart recovery; do not stop the demo server during the presentation. |
| 6:20–7:00 | Close with the message lifecycle and explain the next team tasks: read owned modules, answer viva questions, take screenshots, assemble the report, and rehearse. |

## Failure fallbacks

| Failure | Recovery |
|---|---|
| Port 7443 is already in use | Stop the old Ember server. If another application owns it, launch manually with `--port 7444` and update each client to `--port 7444`. The included `run-demo` scripts use 7443. |
| Keystore is missing | Run `scripts\gen-keys.bat` or `scripts/gen-keys.sh`; it creates local ignored keys and a random password when `EMBER_KEYSTORE_PASS` is unset. Then rerun the demo. If `setx` was used on Windows, open a new terminal because the variable is not added to an existing one. |
| Wrong or missing JDK | Install/use the team's JDK 21, check `java -version` and `javac -version`, then rerun. `scripts/package-demo` creates a bundled-runtime app-image for the client laptop; it does not remove the JDK requirement for building. |
| Projector resolution is too small or scaled | Use Windows Display Settings to select 1280×720 or higher and set scaling to 100% for the demo. Maximize the client windows; use the 1280×720 layout if controls are clipped. |
| No internet/network | No external network is required. Confirm the server listens on 127.0.0.1 and clients use `localhost`; disable VPN/proxy interference if loopback is redirected. |
| A client window fails to start | Keep the server window visible and run `scripts\run-tests.bat`; the headless TLS integration test exercises registration and message delivery. Continue with the saved seeded post and story if the feed view is unavailable. |

## Rehearsal checklist

- Run the full suite once: `scripts\run-tests.bat` (or `scripts/run-tests.sh`).
- Confirm the server log says it is listening on `127.0.0.1:7443` and all three clients sign in.
- Use `docs/screenshots/` only as fallback evidence; the team must capture current real UI screenshots from its demo machine.
- Do not describe the duplicate resend check as a replay attack, and do not claim SQL-injection protection. Track B uses no SQL; hostile input is treated as literal text and usernames are allowlist-validated.
