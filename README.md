# Ember

**Say it. Spark it. Let it fade.**

Ember is a Java desktop messenger and campus social feed.

The current build provides TLS 1.3 transport, a bounded wire protocol, registration/login, a themed Swing chat workspace, live 1:1 and group messaging, server-assigned order, sent/delivered/read receipts, duplicate resend handling, a durable local outbox, session resume and sync, history pulls, presence, typing, heartbeat, public profiles and follows, image posts, likes, and comments. The server uses CRC-protected WAL storage, username lockout, message rate limits, and authentication audit events. Campus Pulse images are checked by signature and size, then re-encoded.

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
```

On Unix-like systems, use the matching `scripts/build.sh`, `scripts/run.sh`, `scripts/run-tests.sh`, `scripts/run-server.sh`, and `scripts/run-client.sh` scripts.

## TLS development keys

The transport uses a local server keystore and client truststore. Run `scripts/gen-keys.bat` (or `scripts/gen-keys.sh`) once. The script reads `EMBER_KEYSTORE_PASS` from the environment, then from the git-ignored `config/secrets.properties`; if neither is present, it creates a random password there. On Windows, `setx EMBER_KEYSTORE_PASS value` only affects new terminals, so open a new terminal before running the script. The server keystore and secrets file are local-only and git-ignored; the generated certificate and client truststore are safe to commit for the offline demo. Do not reuse the generated password elsewhere.

## Security claims

Implemented claims: “Traffic between client and server is encrypted with TLS 1.3.” “Passwords are stored with salted PBKDF2-HMAC-SHA256 at 600,000 iterations, the OWASP work factor for that algorithm. OWASP's first choice is Argon2id, which the JDK does not include.” “The wire protocol is length-prefixed with size limits and does not use Java serialization.” “Every command that names an object is authorization-checked on the server, with a negative test per rule.” “Uploaded images are validated by signature and size, then re-encoded.” Phase 6 profiles, feeds, and posts are public; private-account filtering and blocking are not implemented yet.
