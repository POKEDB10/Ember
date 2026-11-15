# Abstract

Ember is a Java desktop messenger and campus social application designed for an offline, single-laptop demonstration. It provides TLS 1.3 sockets, a bounded length-prefixed protocol, a UTF-8 key/value codec, PBKDF2 password hashing, registration, live direct messages, and CRC-protected write-ahead-log storage using JDK APIs. The zero-dependency test runner verifies Arabic and emoji round trips through the codec, WAL, and client model; a real 2 MiB TLS exchange; server restart recovery; username lockout; escaped audit events; and a message flood receiving error 429. The client trusts a project-specific certificate store and rejects the JVM default truststore. Later phases will add reconnect synchronization, groups, richer chat screens, and the Campus Pulse feed. The project is designed as a teaching system: its controls are described precisely and its code remains explainable to students.

**Current word count:** 129.  
**Final target:** 100–150 words.
