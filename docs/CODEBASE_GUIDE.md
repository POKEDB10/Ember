# Ember, explained simply

This guide explains the whole project in plain words, so anyone on the team can explain any part of it in the demo. No prior knowledge needed. Read it top to bottom once; after that, use the headings to jump around.

---

## 1. The one-minute version

Ember is a chat app (like WhatsApp) plus a small campus photo feed (like Instagram), plus voice and video calls.

There are three programs:

| Program | What it is | Where the code is |
|---|---|---|
| **Server** | One Java program that runs on a computer on the internet (Render). Everyone connects to it. It checks every request, stores the messages, and passes them on. | `src/main/java/edu/ember/server/` |
| **Website** | Plain HTML/CSS/JavaScript that the server sends to your browser. You can "install" it like an app. | `src/main/resources/web/` |
| **Desktop app** | A Java Swing window app that talks to the same server. | `src/main/java/edu/ember/client/` |

Code used by both the server and the desktop app (message format, security helpers, data classes) lives in `src/main/java/edu/ember/common/`.

**Analogy:** the server is a post office. Apps are people sending letters. The post office checks who you are, checks you are allowed to send to that person, stamps a number on the letter, keeps a copy in its filing cabinet, and delivers it.

---

## 2. The folders

```
ember/
├── src/main/java/edu/ember/
│   ├── common/      shared by server and desktop app
│   │   ├── protocol/    the "language" apps and server speak (frames)
│   │   ├── security/    passwords, TLS, input checks, image checks
│   │   ├── model/       plain data classes: Message, UserProfile, GroupChat...
│   │   ├── exceptions/  our own error types
│   │   └── util/        small helpers (logging, tiny JSON writer)
│   ├── server/      the server
│   │   └── storage/     how data is saved (memory, log file, rootz backup)
│   ├── client/      the Swing desktop app
│   │   └── ui/          every window, panel and button of the desktop app
│   └── tools/       demo and proof programs (attack simulator, stress test, demo data)
├── src/main/resources/web/   the website (index.html, app.js, call.js, app.css, sw.js...)
├── src/test/java/            the automated tests (37 of them)
├── scripts/                  build/run/test scripts (.bat for Windows, .sh for Linux/Mac)
├── docs/                     report, diagrams, screenshots, this guide
├── Dockerfile, render.yaml   how Render builds and runs the server
└── config/                   keys and secrets, NOT on GitHub (git-ignored)
```

---

## 3. The language: frames

The apps and the server send each other **frames**. A frame is a small packet of bytes with a fixed 18-byte header followed by the content.

```
| magic "EMBR" (4 bytes) | version (1) | opcode (1) | correlation id (8) | length (4) | payload... |
```

- **magic**: always the letters EMBR. If a frame starts with anything else, the server knows it is garbage and drops the connection.
- **opcode**: one number that says what this frame is. `0x10` means "send a message", `0x43` means "here is a message for you". The full list is in `common/protocol/WireOpcodes.java`. Numbers `0x01–0x3F` are **commands** (app → server); `0x40` and up are **events** (server → app).
- **correlation id**: a ticket number. When the app sends command #7, the server's reply also carries #7, so the app knows which question the answer belongs to.
- **length**: how many bytes of payload follow. The server refuses anything too big *before* reading it: 4 KB before you log in, 25 MB after.
- **payload**: usually simple text lines like `convId=dm:alice:bob` and `body=hello`. Encoding and decoding this is `KvPayloadCodec.java`.

Files: `FrameCodec.java` reads and writes frames, `WireFrame.java` is one frame (immutable, made with the Builder pattern), `FrameFactory.java` builds frames from fields.

**Why our own format and not Java serialization?** Java serialization can be tricked into running code from attacker data. Our format is just numbers and text with size limits, so it cannot.

---

## 4. How a connection works

1. The app opens a connection to the server:
   - The desktop app uses a direct **TLS 1.3** socket (port 7443).
   - The website uses a **WebSocket** over HTTPS (`wss://`), which is how browsers keep a live two-way connection open. It carries exactly the same frames. Translating between WebSocket and the frame format is `server/WebGateway.java`.
   - Either way, everything on the wire is encrypted.
2. Before logging in, the app may only send small frames (4 KB).
3. It sends **REGISTER** or **LOGIN** with username and password. The server answers with **AUTH_OK** and a random 32-byte **session token**.
4. If the connection drops, the app reconnects and sends **RESUME** with the token, so the user does not type the password again. Tokens die after 30 minutes unused or 12 hours total (`SessionStore.java`).
5. While connected, the app pings every 20 seconds. The server drops connections that are silent for 45 seconds.

On the server, each connection gets its own thread: a `ConnectionHandler`, which is an inner class inside `EmberServer.java`. It reads frames in a loop and hands each one to `route()`. `route()` is one big `switch` on the opcode that calls the right method (`sendMessage`, `typing`, `createGroup`, `call`...).

**Multiple devices:** the server keeps a map `userId → set of open connections`. When something is sent "to Alice", it goes to every device Alice is signed in on (`sendToUser`).

---

## 5. The life of one message (the most important flow)

Alice types "hi" to Bob on the website:

1. **Website** (`app.js`, `sendText`): it makes a random ID for the message, shows it immediately with a grey "…" (pending), and sends a **MSG_SEND** frame: `clientMsgId`, `convId=dm:alice:bob`, `body=hi`.
2. **Server** (`EmberServer.sendMessage`):
   - Is Alice logged in? If not, error 401.
   - Is she sending too fast? A **token bucket** allows 20 quick messages, then refills at 5 per second. Too fast gets error 429.
   - Is she allowed in this conversation? `dm:alice:bob` must contain her name, and neither person may have blocked the other. Groups require membership.
   - Has she already sent this exact ID? Apps resend after reconnecting, so a repeat ID does not create a duplicate; the original is acknowledged again.
   - It **saves** the message (storage, section 8). That assigns a sequence number, so everyone sees messages in the same order.
   - It sends **MSG_ACK** back to Alice. Her "…" becomes ✓.
   - It sends **MSG_DELIVER** to every one of Bob's devices, and to Alice's *other* devices.
   - If Bob has no device connected, it sends a **Web Push** notification (section 10).
3. **Bob's app** shows it and sends a **RECEIPT** (DELIVERED, or READ if the chat is open). The server forwards it to Alice, and her ✓ becomes ✓✓.

Conversation IDs: direct chats are `dm:` plus the two usernames in alphabetical order (`dm:alice:bob`). Groups are `grp:` plus an ID.

---

## 6. Every feature in one line each

| Feature | How it works | Main code |
|---|---|---|
| Register / login | Username allowlist (`a-z 0-9 _`, 3–20 chars). Password hashed with PBKDF2, 600,000 rounds, random salt. | `AuthService`, `Pbkdf2PasswordHasher`, `InputValidator` |
| Lockout | 5 wrong passwords in 10 minutes locks that username for 15 minutes. | `UserLockManager` |
| Direct and group chat | As in section 5. Groups have a moderator who adds and removes members. | `EmberServer`, `GroupService` |
| Receipts ✓ / ✓✓ | Delivered and read receipts. In groups, ✓✓ only once everyone has read it. | `GroupReceiptTracker` |
| Typing… | Sent at most once every 3 seconds, only to people in that chat. | `EmberServer.typing` |
| Presence and status | Online, away (manual or after 5 minutes idle), do not disturb, invisible (looks offline to others). | `EmberServer.presenceEvent` |
| Reply, react, edit, delete | Each is its own command. The server checks you own the message (edit/delete) or are in the chat (react). | `EmberServer` |
| Pins and stars | Pins are seen by everyone in the chat; stars only by you, on all your devices. | `EmberServer`, storage |
| Disappearing messages | 15-second timer; a background thread deletes them and tells every app. | `ExpiryReaperThread` |
| Polls | Group-only. The votes are a 2-D array (members × options). | `PollData` |
| Search | Searches messages already downloaded to the device (all words must match). | `SearchEngine` (desktop), `app.js` (web) |
| Campus Pulse | Photo posts, likes, comments. | `SocialFeedService` |
| Stories | Photos that vanish after 24 hours. | `SocialFeedService`, `StoryItem` |
| Private profiles and follow requests | Private accounts approve followers before their posts are visible. | `SocialFeedService` |
| Block and report | Blocked users cannot message, call, or see each other. Reports go to admins. | `SocialFeedService`, `AdminConsolePanel` |
| Photo upload | Checked by file signature (not file name), size limits, then **re-encoded** as a fresh JPEG, so hidden payloads are destroyed. | `ImageValidator` |
| Notifications | In-page card with quick reply; system notification when the tab is in the background; Web Push when no tab is open. | `app.js`, `sw.js`, `WebPush` |
| Offline cache | The website keeps chats in the browser (IndexedDB) and on return asks only "what changed since revision N". | `app.js` (`cache`), `EmberServer.syncStart` |
| Themes | 7 retro themes on the web, 2 on desktop. | `app.css`, `ThemeManager` |
| **Voice and video calls** | Section 7. | `call.js`, `call-worklet.js`, `CallRegistry` |

---

## 7. Voice and video calls

### The short version
Press 📞 or 🎥 in a direct chat. The other person's website rings. When they answer:

1. **Lowest delay first:** the two browsers try to connect **directly** to each other (WebRTC, peer-to-peer). Audio and video then do not pass through our server at all, so the delay is only the distance between the two people.
2. **If that is blocked** (some networks, including many in the UAE, block calling apps and their UDP traffic), the call switches to the **relay** within about 6 seconds: the audio and video packets travel inside the normal HTTPS WebSocket the website already uses. To the network it looks like an ordinary web page.

Either way the call is **end-to-end encrypted**: our server cannot listen.

### Step by step
1. **Ring.** Alice's browser sends `CALL kind=ring`. The server checks the same rules as messages (DM only, no blocks), plus a rate limit (5 rings, then 1 per 20 s). It rings only Bob's devices that can take calls (website, not the Swing app). If Bob is on do not disturb or has no such device, Alice hears "cannot take calls" and Bob gets a "missed call" push. Unanswered calls stop after 45 s.
2. **Answer.** Bob presses ACCEPT. The server links exactly *one* of Bob's devices with Alice's device; his other devices stop ringing.
3. **Keys.** Each browser makes a brand-new key pair for this call (ECDH P-256) and sends the public half. Both compute the same secret key (HKDF, then AES-GCM 128). The server only ever sees the public halves, so it cannot compute the key.
4. **Safety code.** Both screens show 12 digits made from the two public keys. If the codes match, nobody (not even the server) swapped the keys, so nobody is in the middle. Users can read them out to each other.
5. **Everything after that is encrypted with the call key**, including the network addresses (ICE candidates) for the direct link. So the server never learns your IP address from a call.
6. **Direct link** (WebRTC): DTLS-SRTP encryption (built into every browser). Its fingerprints are inside the encrypted signals, so the server cannot fake them either.
7. **Relay** (if direct fails, or if "Hide my IP" is on): the browser encodes audio with **Opus** and video with **VP8/H.264** itself (WebCodecs), encrypts each packet with AES-GCM, and sends it as a `CALL_MEDIA` frame. The server forwards the bytes **without reading them** (it cannot) to the one linked device.

### Low data use
| | Direct | Relay |
|---|---|---|
| Voice | ~20 kbit/s (Opus, mono, silence suppression) | ~25–35 kbit/s |
| Voice + video (320×240, 15 fps) | ~170 kbit/s | ~195 kbit/s |
| Muted microphone | ~0 | 0 |

A one-hour voice call is roughly 10–15 MB. Memory: no libraries; video frames are freed right after use; everything (camera, microphone, codecs, timers) is released when the call ends.

### Keeping the delay low
- Direct whenever the network allows it.
- Audio is sent in 20 ms pieces; the player holds only 40–160 ms and drops older audio rather than falling behind (`call-worklet.js`).
- When the line is congested, video frames are skipped *before* encoding (audio always wins).
- The server sends frames immediately (`TCP_NODELAY`) and does not compress call packets (encrypted data does not compress).
- `render.yaml` puts the server in Render's Singapore region, the closest to the UAE.

### Safety rules the server enforces for calls (`CallRegistry.java`)
- One call per person at a time ("busy" otherwise).
- Only the two linked devices can signal or send media; an outsider gets "call not found".
- At most 400 signalling messages per call; at most ~1 Mbit/s of relayed media per direction; packets over 64 KB are dropped.
- Calls end when either device disconnects.

### Packet protection (in `call.js`)
Each encrypted packet starts with a direction byte (caller = 0, callee = 1) and a counter.
- A packet with the wrong direction is rejected, so the server cannot bounce your own audio back at you.
- A packet with an old counter is rejected, so a recording cannot be replayed into the call.
- The header is authenticated, so it cannot be changed without detection.

### A note on the UAE
In the UAE, voice and video calling services are regulated by the TDRA, and unlicensed VoIP apps are restricted. Ember's relay mode uses ordinary HTTPS (like any website), which is why it still connects on networks that block VoIP ports. That is fine for a university project demo, but **do not run Ember as a public calling service in the UAE** without checking the rules with the university or TDRA.

---

## 8. Storage: where data lives

`server/storage/StorageEngine.java` is an **interface** (a list of methods like `appendMessage`, `getHistory`). There are two implementations, swapped without changing the rest of the server (polymorphism):

- **`InMemoryStorageEngine`**: keeps everything in Java collections in RAM. Used by tests and demos.
- **`WalStorageEngine`**: the real one. It extends the in-memory one and also writes every change to a **write-ahead log** (WAL). That is a file where each change is appended as a record with a **CRC checksum**.
  - On startup it replays the log to rebuild memory.
  - If the computer crashed mid-write, the last half-written record fails its checksum and is cut off ("torn tail"), so the data is never corrupted.
  - Every so often the whole state is written as a compressed **snapshot**, and old log files are deleted.

**Off-server backup (rootz.so):** Render's free disk is wiped on every restart, so `BlobStoreReplica` copies the WAL to rootz.so every 2 seconds. Before uploading, the data is **GZIP-compressed and AES-256-GCM encrypted** with `EMBER_STORAGE_KEY`, so rootz only ever stores unreadable `ember-*` files. On startup, the server downloads the newest snapshot plus later logs and rebuilds everything. `RootzBlobStore` is the code that talks to rootz's API; `BlobStore` is the interface, so another provider could be plugged in.

---

## 9. Security, all in one place

| Threat | What stops it |
|---|---|
| Someone reads traffic on the Wi-Fi | TLS 1.3 everywhere (desktop socket and HTTPS/WSS). Calls are additionally end-to-end encrypted. |
| Stolen database leaks passwords | Passwords are never stored. Only salted PBKDF2-SHA256 hashes (600,000 rounds) are kept. |
| Password guessing | 5 failures lock the account for 15 minutes; login attempts are rate-limited per IP. |
| "Does this username exist?" probing | Login failures give the same message either way; private or blocked profiles say "not found" instead of "forbidden". |
| User A reads or edits user B's stuff | Every command that names an object (message, group, post, call) is checked on the server against who is logged in. The test suite has a negative test per rule. |
| Malicious frames or giant uploads | Strict frame header, size limits before reading, no Java serialization. |
| Malware hidden in images | File signature check, dimension limits before decoding, and re-encoding to a clean JPEG. |
| Script injection (XSS) in the website | The website never builds HTML from text: every message is inserted as plain text (`textContent`). A strict **Content-Security-Policy** allows only our own scripts anyway. |
| Another website using your login (CSWSH) | The server refuses WebSocket connections whose `Origin` is a different website. Auth uses a token in the frame, not a cookie. |
| Clickjacking / embedding our page | `frame-ancestors 'none'`. |
| Other headers | `Strict-Transport-Security` (HTTPS only), `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Permissions-Policy` (camera and microphone only for our own page, no location), `Cross-Origin-Opener/Resource-Policy: same-origin`. |
| Spam and floods | Token buckets for messages (20 burst, 5/s), reports, calls, and call media. |
| Shared computer | Logout deletes the device's chat cache and push subscription, and kills the session on the server. |
| Backup provider reads our data | AES-256-GCM before upload; the key never leaves our server settings. |
| Secrets on GitHub | `config/` and `secrets.properties` are git-ignored. |
| Audit trail | `SecurityAuditLogger` writes one JSON line per security event (failed login, lockout, denied access, rate limit) and never writes passwords. |

### Honest limits (say these if asked)
- **Chat messages are not end-to-end encrypted** (only calls are). They are encrypted on the network and in the backup, but the server itself can read them, so it can deliver them to every device and sync history. True end-to-end encryption for multi-device chat (like Signal's) is a big project of its own.
- Everyone online can see who else is online (the "invisible" status hides you).
- The session token is kept in the browser's localStorage. That is safe as long as no script injection exists, which the CSP and the text-only rendering prevent.
- Direct calls ask public STUN servers (Cloudflare, Google) "what is my public address?". "Hide my IP" skips that and always uses the relay.

---

## 10. Notifications (Web Push)

When none of your devices is connected, the server sends a push through your browser's push service (Google, Mozilla or Apple). The content is encrypted for your browser only (RFC 8291), and the server proves who it is with a signed token (VAPID, RFC 8292). The service worker (`sw.js`) shows the notification with **Reply** and **Mark read** buttons. Code: `server/WebPush.java`.

---

## 11. The website files

| File | What it does |
|---|---|
| `index.html` | The page skeleton: login form, chat list, chat area, call window. |
| `app.css` | The retro look and the 7 themes (CSS variables per theme). |
| `app.js` | Everything else: frame encode/decode, connection and reconnect, the state (`S`), rendering, chats, Pulse, profiles, notifications, and the IndexedDB cache. |
| `call.js` | Voice and video calls (section 7). |
| `call-worklet.js` | Runs on the browser's audio thread: cuts the microphone into 20 ms pieces and plays incoming audio with a small jitter buffer. |
| `sw.js` | Service worker: keeps the app files offline and shows push notifications. |
| `manifest.json`, `icon.svg` | Let the browser install Ember as an app. |

The server only serves these exact files (an allowlist), so `../../secret` style paths are impossible.

---

## 12. The desktop app (Swing), in MVC terms

- **Model**: `ClientStateModel`. It holds conversations, messages and presence, and is thread-safe.
- **View**: everything in `client/ui/` (`ChatWorkspacePanel` holds `ChatListPanel` and `ConversationPanel`; also `CampusPulsePanel`, `ProfilePanel`, `SettingsPanel`...).
- **Controller**: `ClientController`, the Facade the views call (`sendMessage`, `login`...).
- **Threads**: `OutboundSenderThread` writes frames from a queue (producer-consumer), `InboundReceiverThread` reads them, `ReconnectManager` retries with exponential backoff. The UI is only touched on Swing's Event Dispatch Thread.
- **Offline**: `LocalOutbox` saves unsent messages to a file and sends them after reconnecting.
- **Connection**: `ServerLink` is an interface with two implementations, a TLS socket or the website's WebSocket (`--url https://...`).
- The desktop app does not do calls. Calls ring only on the website.

---

## 13. Course concepts and where to point

| Concept | Example in Ember |
|---|---|
| Classes, encapsulation | `Message`, `GroupChat`, `SessionStore` (private fields, methods guard them) |
| Inheritance | `WalStorageEngine extends InMemoryStorageEngine`; exception tree under `EmberException` |
| Interfaces and polymorphism | `StorageEngine`, `BlobStore`, `PasswordHasher`, `ServerLink` |
| Abstract and immutable data | `WireFrame` (Builder pattern), records like `UserRecord` |
| Generics | `CallRegistry<D>` (works with any "device" type, so it is testable without sockets) |
| Inner classes | `EmberServer.ConnectionHandler`, `CallRegistry.Call` |
| Exceptions | Our own checked exceptions mapped to error codes 400/401/403/404/423/429 |
| Threads and synchronization | one thread per connection, `AutoSaveThread`, `ExpiryReaperThread`, `synchronized` in `TokenBucket`/`CallRegistry`, `ConcurrentHashMap` |
| Collections | maps of sessions, sets of devices, queues in the outbox |
| File I/O and streams | WAL with `FileChannel`, `DataOutputStream` frames, GZIP |
| Networking | `SSLServerSocket`, WebSocket handshake by hand, HTTP for the website |
| Design patterns | Facade (`ClientController`), Observer (model listeners), Strategy (`PasswordHasher`, themes), Builder (`WireFrame`), Factory (`FrameFactory`, `TlsSocketFactory`), Adapter (`WebGateway`), DAO (`StorageEngine`), MVC (desktop) |
| GUI | Swing panels, custom painting (`AvatarComponent`, `RoundedPanel`), `SwingWorker` |

---

## 14. Running it

```
scripts\build.bat                       compile everything into build\classes
scripts\run-tests.bat                   run the 37 automated tests
scripts\run-demo.bat                    server + 3 desktop clients with demo data (alice / alice-demo-42)
scripts\run-server.bat --no-tls --http-port 8080 --storage data   website at http://localhost:8080
```

Calls need two browsers (or two different addresses, such as `localhost` and `127.0.0.1`), each signed in as a different user. Browsers only allow the camera and microphone on `https://` or `localhost`.

## 15. Questions you may be asked in the demo

- **Why a custom protocol?** It is small and fast, has hard size limits, and avoids Java deserialization attacks.
- **What happens if the server crashes mid-write?** The WAL checksum detects the half record and trims it. Everything before it is intact.
- **How do two devices stay in sync?** Every change has a revision number; a device asks "changes since N".
- **How are calls secure if they go through your server?** The key is made by the two browsers (ECDH); the server only forwards encrypted bytes and cannot derive the key. The safety code proves nobody swapped keys.
- **Why is the call fast?** It goes direct (peer-to-peer) when possible. The relay sends small 20 ms audio pieces, keeps the playback buffer short, and drops late audio instead of queueing it.
- **Why does it work where VoIP is blocked?** The fallback uses the same HTTPS connection as the website itself, on port 443.
- **What would you add next?** End-to-end encrypted chats, group calls, and a TURN server for direct calls through strict firewalls.
