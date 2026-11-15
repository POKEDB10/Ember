# Architecture

## Phase 0 scaffold

Ember is a Java 17-compatible Swing desktop client and TLS server project, built with a JDK 21 toolchain and `javac --release 17`. Track B uses only JDK APIs. The package root is `edu.ember`; product identity lives in `AppInfo` so the name and tagline can be changed centrally.

The source layout separates shared protocol, model, security, and utility code from server, storage, client networking, Swing UI, tools, and a zero-dependency test runner. Later phases implement those packages behind the frozen interfaces below. `AppLog` provides named `java.util.logging` loggers. No Java serialization is permitted.

## Frozen interfaces

- `StorageEngine` (planned): `void init() throws IOException`; `void saveUser(UserRecord user) throws IOException`; `Optional<UserRecord> findUser(String username) throws IOException`; `long appendMessage(Message message) throws IOException`; `void updateMessageStatus(String messageId, MessageStatus status) throws IOException`; `Optional<Message> findBySenderAndClientMsgId(String senderUserId, String clientMsgId) throws IOException`; `List<WireFrame> getDeltaFramesSince(String userId, long sinceGlobalSeq) throws IOException`; `List<Message> getHistory(String convId, long beforeSeq, int limit) throws IOException`; `int purgeExpiredRecords(long nowMs) throws IOException`; `void close() throws IOException`.
- `PasswordHasher`: `String hash(char[] password)`; `boolean verify(char[] password, String encodedHash)`; `String dummyHash()`.
- `ClientModelListener` (planned): `void onModelChanged(ClientStateModel model, ModelChange change)`.
- `FrameCodec`: `static WireFrame readFrame(InputStream input, int maxPayloadBytes) throws IOException, EmberException`; `static void writeFrame(OutputStream output, WireFrame frame) throws IOException`.
- `ClientController` (planned): `void login(String username, char[] password)`; `void register(String username, char[] password, String displayName)`; `void sendMessage(String conversationId, String body)`; `void openConversation(String conversationId)`; `void markRead(String conversationId, String messageId)`; `void setTyping(String conversationId, boolean typing)`; `void logout()`; `void addListener(ClientModelListener listener)`; `void removeListener(ClientModelListener listener)`.

`PasswordHasher` and `FrameCodec` signatures are implemented in Phase 1. The remaining signatures document frozen responsibilities and will be checked against their eventual model types when those APIs are introduced.

## Thread and UI rules

All Swing mutations run on the event dispatch thread. Network, disk, password hashing, and image decoding stay off the EDT. Server state locks protect short in-memory updates; blocking socket I/O is never performed while holding a state lock. Use named platform threads; virtual threads are future work.

## Build

Use `scripts/build.bat` or `scripts/build.sh`. Both compile with `--release 17 -encoding UTF-8 -Xlint:all,-serial -Werror`. `scripts/run-tests` runs the zero-dependency `EmberTestSuite`. Phase 1 supplies a TLS 1.3 socket factory, bounded frame codec, UTF-8 KV codec, PBKDF2 password hasher, and input validator. The TLS loopback test uses the generated Ember truststore and confirms the JVM default truststore rejects the self-signed certificate.
