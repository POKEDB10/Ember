# Architecture

## Phase 0 scaffold

Ember is a Java 17-compatible Swing desktop client and TLS server project, built with a JDK 21 toolchain and `javac --release 17`. Track B uses only JDK APIs. The package root is `edu.ember`; product identity lives in `AppInfo` so the name and tagline can be changed centrally.

The source layout separates shared protocol, model, security, and utility code from server, storage, client networking, Swing UI, tools, and a zero-dependency test runner. Later phases implement those packages behind the frozen interfaces below. `AppLog` provides named `java.util.logging` loggers. No Java serialization is permitted.

## Frozen interfaces

- `StorageEngine`: `init`, `saveUser`, `findUser`, `appendMessage`, `updateMessageStatus`, `findBySenderAndClientMsgId`, `getDeltaFramesSince`, `getHistory`, `purgeExpiredRecords`, `close`.
- `PasswordHasher`: `hash`, `verify`, `dummyHash`.
- `ClientModelListener`: client-model change notification.
- `FrameCodec`: `readFrame`, `writeFrame`.
- `ClientController`: `login`, `register`, `sendMessage`, `openConversation`, `markRead`, `setTyping`, `logout`, `addListener`, `removeListener`.

The interface names and method responsibilities are frozen at scaffold time; Java signatures will be declared with their model types when each module is implemented.

## Thread and UI rules

All Swing mutations run on the event dispatch thread. Network, disk, password hashing, and image decoding stay off the EDT. Server state locks protect short in-memory updates; blocking socket I/O is never performed while holding a state lock. Use named platform threads; virtual threads are future work.

## Build

Use `scripts/build.bat` or `scripts/build.sh`. Both compile with `--release 17 -encoding UTF-8 -Xlint:all,-serial -Werror`. `scripts/run-tests` runs the zero-dependency `EmberTestSuite`.
