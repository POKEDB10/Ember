# Viva notes

## Phase 0: scaffold

The scaffold establishes the package root, shared product identity, plain `javac` build, and a zero-dependency test entry point. `AppInfo` keeps the product name and tagline in one place; `EmberScaffold` is a temporary runnable entry point until the server and client are added.

**Key classes:** `AppInfo`, `EmberScaffold`, `EmberTestSuite`.

**Trace:** `scripts/run` compiles main sources, then starts `EmberScaffold.main`; the entry point reads `AppInfo.NAME` and `AppInfo.TAGLINE` and prints the banner.

**Concepts:** packages, classes, constructors, `static`, `final`, command-line entry point.

### Likely questions

1. **Why centralize the app name and tagline?** Both values are used across UI, logs, and documentation. Keeping the runtime values in one class makes a rename easy and avoids inconsistent literals.
2. **Why use plain `javac` instead of Maven or Gradle?** The course constraint is JDK APIs only, and a direct compiler command makes the build steps visible to students. It avoids dependency and build-tool setup for an offline demo.
3. **Why is the test runner zero-dependency?** It works with only the JDK on the demo laptop. As features arrive, small assertions and test cases can be added without a separate test framework.

## Phase 1: transport and security primitives

`FrameCodec` reads the fixed-size header, validates magic, version, and payload length before allocating the payload, then reads it fully. `KvPayloadCodec` encodes escaped UTF-8 fields. `TlsSocketFactory` enables TLS 1.3 with Ember's own truststore and HTTPS endpoint identification, while `Pbkdf2PasswordHasher` stores salted PBKDF2-HMAC-SHA256 hashes at 600,000 iterations and provides a fixed dummy verification hash.

**Key classes:** `WireFrame`, `FrameCodec`, `KvPayloadCodec`, `TlsSocketFactory`, `Pbkdf2PasswordHasher`, `InputValidator`, and the checked `EmberException` hierarchy.

**Trace:** a client creates a `WireFrame`, `FrameCodec` writes its 18-byte header and payload to a TLS socket, the peer checks the header limit before allocating, and `KvPayloadCodec` decodes UTF-8 fields after the frame arrives.

**Concepts:** binary streams, Builder and Factory patterns, `StringBuffer`, `StringTokenizer`, UTF-8, PBKDF2, TLS, regular expressions, checked exceptions.

### Likely questions

1. **Why check the payload length before creating its byte array?** The length comes from untrusted network input. Checking first prevents a peer from asking the client or server to allocate an oversized buffer.
2. **Why does the client use its own truststore and endpoint identification?** The truststore limits trust to the demo server certificate, and endpoint identification checks that the certificate matches the requested host. The TLS loopback test also confirms the JVM default truststore rejects the self-signed Ember certificate.
3. **Why use PBKDF2 and a dummy hash for unknown users?** PBKDF2-HMAC-SHA256 is the JDK-native choice under the no-library rule, with the specified 600,000 work factor. Verifying against a fixed dummy hash keeps the unknown-account path doing password-hash work instead of returning immediately.

## Phase 2: walking skeleton

`EmberServer` accepts TLS connections and gives each connection a named reader task. `AuthService` registers and authenticates accounts through the `StorageEngine` interface. `ClientController` sends commands through one outbound sender and receives events through one inbound receiver; model listeners are notified on the Swing EDT. The headless test registers Alice and Bob, sends “Hello”, observes it at Bob, and waits for Alice's DELIVERED receipt.

**Key classes:** `EmberServer`, `ConnectionHandler`, `AuthService`, `SessionStore`, `InMemoryStorageEngine`, `ClientController`, `ClientStateModel`, `OutboundSenderThread`, `InboundReceiverThread`, `EmberClientApp`.

**Trace:** Alice registers over TLS → the server hashes and stores the account → login success binds user identity to the connection → Alice sends a message → the server assigns sequence values and acknowledges it → Bob receives it and sends a delivery receipt → Alice sees DELIVERED.

**Concepts:** interfaces and DAO, thread pools, platform threads, `wait/notify`, Observer/MVC, Swing EDT, `SwingWorker`, synchronized state.

### Likely questions

1. **Why is identity bound to the connection?** The server sets the authenticated user from the login result and uses that value for later commands. The client does not choose a sender identity in a message frame.
2. **Why does the client use one sender thread and one receiver thread?** One writer prevents frames from interleaving on the socket, and one reader gives a clear place to process server events. The sender queue lets UI actions enqueue work without writing network bytes on the EDT.
3. **Why use a SwingWorker for the connection and keep model callbacks on the EDT?** TLS handshakes and socket work can block, so they must not freeze the interface. Swing component updates belong on the event dispatch thread; the model schedules listener notifications there.
