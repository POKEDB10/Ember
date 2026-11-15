# Ember

**Say it. Spark it. Let it fade.**

Ember is a Java desktop messenger and campus social feed. The project is being built in gated phases; this Phase 0 scaffold currently demonstrates the build, runner, and test entry point. It does not yet provide chat or a server.

## Requirements

- JDK 21 toolchain (compiled with `--release 17`)
- Windows scripts are provided in `.bat` form; matching `.sh` scripts are also included.

## Build and run

```text
scripts/build.bat
scripts/run.bat
scripts/run-tests.bat
```

On Unix-like systems, use `scripts/build.sh`, `scripts/run.sh`, and `scripts/run-tests.sh`.

## TLS development keys

The transport uses a local server keystore and client truststore. Run `scripts/gen-keys.bat` (or `scripts/gen-keys.sh`) once. The script reads `EMBER_KEYSTORE_PASS` from the environment, then from the git-ignored `config/secrets.properties`; if neither is present, it creates a random password there. On Windows, `setx EMBER_KEYSTORE_PASS value` only affects new terminals, so open a new terminal before running the script. The server keystore and secrets file are local-only and git-ignored; the generated certificate and client truststore are safe to commit for the offline demo. Do not reuse the generated password elsewhere.

## Security claims

As features are completed, the project will use these claims: “Traffic between client and server is encrypted with TLS 1.3.” “Passwords are stored with salted PBKDF2-HMAC-SHA256 at 600,000 iterations, the OWASP work factor for that algorithm. OWASP's first choice is Argon2id, which the JDK does not include.” “The wire protocol is length-prefixed with size limits and does not use Java serialization.” “Every command that names an object is authorization-checked on the server, with a negative test per rule.” “Uploaded images are validated by signature and size, then re-encoded.” These are target claims and are not active scaffold features yet.
