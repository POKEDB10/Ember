# Abstract

Ember is a Java messenger and campus social application with a website and a Swing desktop app that share one server. Users can sign in on several devices at once and see the same history live. Direct and group chats support replies, reactions, edits, disappearing messages, polls and search; Campus Pulse adds profiles, photo posts, stories, follows, blocking and reports. The server uses a bounded binary protocol over TLS or WebSockets, PBKDF2 password hashing, and a CRC-checked write-ahead log. For free hosting, the log is batched, compressed, AES-encrypted and stored off-server, so a wiped server rebuilds itself on start-up. The design applies interfaces, inheritance, design patterns, threads, exception handling, file handling and Swing event handling. A zero-dependency test suite checks multi-device delivery, authorization, recovery and attack handling.

**Current word count:** 127.
**Final target:** 100–150 words.
