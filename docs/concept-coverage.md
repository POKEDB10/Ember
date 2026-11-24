# CS F213 concept coverage

Audit of the whole project (24 Nov 2026, before calls v2). Status: **covered** (used for a real reason), **planned** (gap that fits the call work; filled in calls v2), **not applicable** (would be shoehorned). Paths are under `src/main/java/edu/ember/` unless they start with `docs/` or `src/`.

| Topic | Where | Status |
|---|---|---|
| Classes, objects, constructors | everywhere; e.g. `common/model/Message.java` | covered |
| Constructor / method overloading | `server/EmberServer.java` (several constructors chaining with `this(...)`), `client/ClientController.java` (`sendMessage` overloads) | covered |
| `static` members, static utility classes | `common/security/E2eCrypto.java`, `common/security/InputValidator.java`, `common/protocol/WireOpcodes.java` constants | covered |
| Packages | `edu.ember.client`, `.client.ui`, `.server`, `.server.storage`, `.common.{model,protocol,security,exceptions,util}`, `.tools` | covered |
| Arrays, 2-D arrays | `common/model/PollData.java` (`int[][]` vote matrix), byte arrays in `common/protocol/FrameCodec.java` | covered |
| String, StringBuffer, StringTokenizer | `common/protocol/KvPayloadCodec.java` (StringBuffer + StringTokenizer), `client/SearchEngine.java` (StringTokenizer) | covered |
| ArrayList, Vector, LinkedList, iterators | `client/ClientStateModel.java` (Vector of listeners), `client/LocalOutbox.java` + `client/OutboundSenderThread.java` (LinkedList queues), `common/security/ImageValidator.java` (`Iterator<ImageReader>`) | covered |
| Encapsulation | private state + validated setters, e.g. `common/model/GroupChat.java`, `server/SessionStore.java` | covered |
| Inheritance, overriding | `common/exceptions/*` (tree under `EmberException`), `client/ui/RoundedPanel.java` (`paintComponent`) | covered |
| Abstract classes | none yet | **planned**: `QualityPolicy` (abstract base, Data saver / Auto / Max) |
| Interfaces, polymorphism | `server/storage/StorageEngine.java` (two engines), `common/security/PasswordHasher.java`, `server/storage/BlobStore.java`, `client/ServerLink.java` (TLS or WebSocket) | covered; **planned**: `AudioCodec`, `Clock`, `Transport` |
| Comparable | none yet | **planned**: audio frames ordered by sequence number in the jitter buffer |
| Comparator | `client/ui/ChatListPanel.java` (conversation order), `server/storage/InMemoryStorageEngine.java` | covered |
| Inner (member) classes | `server/EmberServer.java` (`ConnectionHandler`, needs the outer server's state) | covered |
| Static nested classes | `common/protocol/WireFrame.java` (`Builder`), `server/CallRegistry.java` (`Call`), `client/CallSession.java` (`Peer`) | covered |
| Anonymous classes | `client/ServerLink.java` (anonymous `InputStream`/`OutputStream`), `client/ui/ConversationPanel.java` (anonymous `SwingWorker`), `client/ui/CallDialog.java` (`WindowAdapter`) | covered |
| Exceptions: throw / throws / finally / user-defined | `common/exceptions/*`, `common/security/Pbkdf2PasswordHasher.java` (`finally` wipes key material), `server/AuthService.java` | covered; **planned**: checked `CallException` hierarchy |
| Object class: equals / hashCode / toString | records generate them (`common/model/*` records); no hand-written overrides yet | **planned**: audio frame `equals`/`hashCode` consistent with `compareTo` |
| Shallow vs deep copy | `common/model/PollData.java` (`voteMatrix()` returns a deep copy, row by row), `client/ClientStateModel.java` (`bytes.clone()` defensive copies) | covered; **planned**: immutable stats snapshots |
| Threads, synchronization, wait/notify | `client/OutboundSenderThread.java` (wait/notify producer-consumer), `server/TokenBucket.java` (`synchronized`), `client/CallSession.java` (capture + mixer threads), `server/AutoSaveThread.java` | covered |
| Swing, delegation event model, layouts | `client/ui/*`: Border/Flow/Box layouts, `CardLayout` in `ChatWorkspacePanel.java`, ActionListeners, `SwingUtilities.invokeLater` | covered |
| Observer | `client/ClientModelListener.java` + `client/ClientStateModel.java` | covered; **planned**: call-stats / quality-change listeners |
| Strategy | `common/security/PasswordHasher.java`, `client/ui/ThemeManager.java` (theme enum), `server/storage/BlobStore.java` | covered; **planned**: quality policies |
| Factory | `common/protocol/FrameFactory.java`, `common/security/TlsSocketFactory.java`, `client/ServerLink.java` (`tls()` / `webSocket()` factory methods) | covered; **planned**: codec negotiation factory |
| Builder | `common/protocol/WireFrame.java` | covered |
| Adapter | `server/WebGateway.java` (WebSocket ↔ frames), `WindowAdapter` in `CallDialog.java` | covered |
| Facade | `client/ClientController.java` | covered |
| Composite | Swing containment (`ChatWorkspacePanel` holds panels holding components) — the JDK's Composite, used, not written | covered (JDK) |
| Decorator | `java.io` stream wrapping: `DataOutputStream` over `BufferedOutputStream` over socket streams (`server/EmberServer.java`, `common/protocol/FrameCodec.java`), GZIP streams in `server/storage/WalStorageEngine.java` | covered (JDK) |
| Command | none yet | **planned**: Swing `Action`s for mute, Speaker mode, hand-off |
| State | message status enum `common/model/MessageStatus.java` | covered; **planned**: call lifecycle states |
| Proxy | no natural use (the server relays bytes but is not a stand-in object for a client) | not applicable |
| Singleton | avoided on purpose: shared state is passed in (testable); `ThemeManager` is a static utility, not a singleton | not applicable |
| Visitor | no type hierarchy that needs double dispatch | not applicable |
| File handling | `server/storage/WalStorageEngine.java` (FileChannel, CRC records), `client/LocalOutbox.java`, `server/SecurityAuditLogger.java`, device key `.properties` in `client/ClientController.java` | covered; **planned**: call settings `.properties`, rotating stats log |
| JDBC | storage is a CRC-checked write-ahead log, not a database; a JDBC driver would break the JDK-only rule | not applicable |
| Program by contract | preconditions via `Objects.requireNonNull` (`common/model/Message.java`), allowlist validation (`common/security/InputValidator.java`) | covered; **planned**: assertions on frame sizes and ladder invariants |
| OO design artefacts | use case `docs/uml/use-case.puml`; class `docs/uml/class-messaging.puml`, report figures 3–4; sequence `docs/uml/sequence-send-deliver-read.puml`; state `docs/uml/state-message-connection.puml`; activity `docs/uml/activity-login-image-upload.puml` | covered; **planned**: CRC cards, call sequence and state diagrams (P4) |
