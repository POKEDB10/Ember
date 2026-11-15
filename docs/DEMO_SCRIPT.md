# Demo script

The seven-minute script and failure fallbacks are completed in Phase 8. Until then, use the phase-specific smoke checks and do not present scaffold behavior as implemented messaging.

## Scaffold smoke check

1. Run `scripts/run` and confirm the Ember name and tagline print.
2. Run `scripts/run-tests` and confirm there are zero failing tests.

## Walking skeleton smoke check

1. Start the server on port 7443 with `scripts/run-server.bat --port 7443`.
2. Launch two clients with `scripts/run-client.bat --host localhost --port 7443 --profile alice` and `--profile bob`.
3. Register both users, send `Hello` from Alice to Bob, and confirm Bob receives it while Alice's status advances to SENT and DELIVERED.
4. If the UI cannot connect, run `scripts/run-tests.bat`; its headless TLS integration test covers the same message path.

The server stores users and messages in `server_data/wal/events.wal` with CRC checks and strict fsync behavior. Do not delete this directory during the demo unless resetting to a clean scenario.
