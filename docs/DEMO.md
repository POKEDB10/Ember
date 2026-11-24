# Ember live demo (about 10 minutes)

**Say it. Spark it. Let it fade.** Two parts on one laptop: the messenger (desktop app over TLS) and calls (website plus desktop app). Nothing needs the internet. Every number you say out loud should come from `docs/results.md`; if a number is not there, say "not measured".

## Before the demo (once, the evening before)

1. `scripts\run-tests.bat`: expect `46 passed, 0 failed` and `fail 0` from the Node tests (Node is optional; without it the script says "skipped").
2. Run both setups below once end to end, then close them.
3. Use headphones for the desktop app (it has no echo cancellation; Speaker mode is the fallback, off by default).
4. Allow camera and microphone for `http://localhost:8090` in Chrome (Site settings), so no prompt appears on stage.

## Part A: messenger (6 minutes)

Start: `scripts\run-demo.bat` (Windows) or `scripts/run-demo.sh`. It builds, makes TLS keys if missing, starts a TLS server on `localhost:7443` with demo data, and opens Alice, Bob and Charlie. Passwords: `alice-demo-42`, `bob-demo-42`, `charlie-demo-42`. The seed never resets existing data.

| Time | What to do and say |
|---|---|
| 0:00–0:40 | Ember is a Java Swing messenger with a campus feed. Three windows, one TLS server, all on this laptop. |
| 0:40–1:35 | Alice and Bob: send `مرحبا 🌋`, show delivered and read ticks. The server orders messages and binds identity to the connection. |
| 1:35–2:25 | Study group: message, reply, reaction, the poll. Only members can read or vote. |
| 2:25–3:15 | Edit and pin a message, search in the chat. Every change is checked on the server. |
| 3:15–4:00 | Campus Pulse post, like and comment, Alice's profile and story. Images are checked by signature and size before decoding. |
| 4:00–4:45 | Admin console as Alice (admin only because this launch passes `--admin-user alice`). |
| 4:45–5:35 | Terminal: `java -cp build\classes edu.ember.tools.AttackSimulator`. Point at the `HANDLED` lines. Say "security by design for a student project", not "as secure as WhatsApp". |
| 5:35–6:00 | `server_data\wal`: CRC-protected log, snapshots. Do not stop the demo server on stage. |

## Part B: calls (4 minutes)

Close Part A's windows. Start a web server with calls v2 and the demo data (plain HTTP on localhost, which browsers treat as secure for camera and microphone):

```text
scripts\run-server.bat --no-tls --http-port 8090 --storage demo_calls_data --demo-seed --calls-v2
scripts\run-client.bat --url http://localhost:8090 --profile bob
```

Open `http://localhost:8090` in Chrome and log in as Alice. Log the desktop app in as Bob.

| Time | What to do and say |
|---|---|
| 6:00–6:40 | Alice starts a new direct chat (**+ DM**, type `bob`), then presses 📞. Bob answers on the desktop. Both show the same **safety code**: the keys were made on the two devices; the server only forwarded public halves. |
| 6:40–7:20 | Ctrl+Shift+D on the website: the stats overlay. Voice is 32 kHz ADPCM both ways (measured about 130 kbit/s), via relay. The desktop has no WebRTC, so it always uses the relay inside the HTTPS WebSocket. That is also what works where calling apps are blocked. |
| 7:20–8:20 | On the desktop press **Video on website**. The browser opens and shows only **Join call**: a link can never join by itself. Press it: the website takes over, the desktop window says "continued on another device", and Alice sees a **new safety code** (new keys for the new device) and Bob's video. |
| 8:20–9:00 | Change Quality to Data saver on the website; within about 1.5 s the overlay shows the lower limit. Explain the four limits: camera, mode, the other side's window, the network. |
| 9:00–10:00 | Close: what we measured and what we did not (`docs/results.md`): Chrome only, one laptop, synthetic media; Firefox/Safari, direct calls on lossy networks and server load on Render were not measured. |

## Failure fallbacks

| Failure | Recovery |
|---|---|
| Port 7443 or 8090 busy | Stop the old server. Otherwise use `--port 7444` / `--http-port 8091` and change the client and browser address to match. |
| Keystore missing (Part A) | `scripts\gen-keys.bat`, then rerun. After `setx`, open a new terminal. |
| Wrong JDK | `java -version` must be 17 or newer (21 recommended). |
| Anything odd in a call | Hang up and call again. If calls v2 misbehaves, restart the Part B server **without** `--calls-v2` (or untick "Calls v2" in either app): every call then uses the older, fully tested path. Hand-off is a calls v2 feature and is then hidden. |
| Camera prompt or no camera | Do the hand-off anyway with the camera allowed; if there is no camera, show the voice call and the overlay only. |
| No "Video on website" button | It appears only for an answered one-to-one call with calls v2 on, when the desktop app was started with `--url`. If the browser does not open, the app shows the address to paste. |
| Echo between laptop speaker and mic | Headphones, or turn on Speaker mode in the desktop Settings. |
| No internet | Not needed for either part. |
| A window fails to start | Keep the server visible and run `scripts\run-tests.bat`; the tests drive the same server code. |

## Things not to say

- Not "unhackable" or "as secure as Signal". Say what is end-to-end encrypted (message text, call audio and video) and what the server still sees (who talks to whom and when).
- Not "SQL-injection proof": there is no SQL. Hostile input stays literal text, usernames are allowlist-checked.
- No numbers that are not in `docs/results.md`.
