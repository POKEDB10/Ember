# Conclusion and future work

Ember now combines direct and group chat with Campus Pulse features, server-side authorization, private-account controls, expiring content, polls, moderation reports, WAL recovery with snapshot compaction, real-TLS attack demonstrations, and a repeatable concurrency stress harness. It runs as a hosted website (installable as an app) and as a Swing desktop app, both connected to one server, with any number of devices per account. Data can be kept off-server in encrypted, compressed batches, which lets the server run on a free host whose disk is wiped on restart. The project still uses only JDK APIs.

Future work: approving follow requests and pinning messages from the website, offline message caching in the browser, voice notes and file attachments, group end-to-end encryption, virtual threads on a later Java target, SQLite through JDBC as an alternative store, and right-to-left layout.
