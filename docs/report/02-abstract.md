# Abstract

Ember is a Java desktop messenger and campus social application designed for an offline, single-laptop demonstration. The project now provides TLS 1.3 sockets, a bounded length-prefixed protocol, a UTF-8 key/value codec, PBKDF2 password hashing, account registration, a basic Swing client, and live direct messages using only JDK APIs. A zero-dependency test runner verifies Arabic and emoji text round trips, password checks, a real 2 MiB TLS loopback exchange, and a headless Alice-to-Bob message with SENT and DELIVERED receipts. The client trusts a project-specific certificate store and rejects the JVM default truststore. Later phases will add durable storage, reconnect synchronization, groups, richer chat screens, and the social feed. The project is designed as a teaching system, so its controls are described precisely and its code remains explainable to the student team.

**Current word count:** 130.  
**Final target:** 100–150 words.
