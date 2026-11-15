# Team ownership

Replace Student A–F with names and roll numbers. Every package has one first owner; shared changes are reviewed by the adjacent owner.

| Role | Owner | Packages and responsibilities |
|---|---|---|
| R1 Protocol and server core | Student A | `common.protocol`, `EmberServer`, `ConnectionHandler`, `ChatRouterService`, `GroupService` |
| R2 Client networking and model | Student B | `client`, reconnect, outbox, model and controller |
| R3 Security and storage | Student C | `common.security`, `server.storage`, authentication, audit and rate limits |
| R4 Chat UI | Student D | Theme, chat list, conversation, settings and shared components |
| R5 Campus Pulse | Student E | Social services, image validation integration, feed/profile/post UI, stories |
| R6 Quality and docs | Student F | Test runner, tools, UML, report kit, demo script and Git hygiene |

Each owner runs and reads their modules after every phase and answers the matching viva questions. Every member can run the full demo and trace a message end to end. Work on `phase-N` branches; merge to `main` only after its gate passes, tag `phase-N-pass`, and use commit messages `phase N: <summary>`.
