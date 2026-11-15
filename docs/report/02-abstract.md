# Abstract

Ember is a Java desktop messenger and campus social application designed for an offline, single-laptop demonstration. Its first implementation phase establishes TLS 1.3 sockets, a bounded length-prefixed wire protocol, a UTF-8 key/value codec, PBKDF2 password hashing, and input validation using only JDK APIs. A zero-dependency test runner verifies protocol validation, Arabic and emoji text round trips, password verification, and a real 2 MiB TLS loopback exchange. The transport trusts a project-specific certificate store and rejects the JVM default truststore. Later phases will add registration, live messaging, persistence, groups, the Swing interface, and the social feed. The project is developed as a teaching system: its controls are described precisely, its security claims are limited to behavior that has been implemented, and its design is documented for a student team to explain and extend.

**Current word count:** 109.  
**Final target:** 100–150 words.
