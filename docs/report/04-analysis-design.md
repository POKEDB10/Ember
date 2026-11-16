# Analysis and design

## Requirements

The implemented requirements include authenticated direct/group messaging, TLS 1.3, CRC-checked WAL recovery and GZIP snapshot rotation, server-side object checks, private profiles and follow approval, blocking and reports, signature- and dimension-checked image uploads, posts, stories, likes, comments, group polls, and in-chat search. The wire protocol and authorization matrix are recorded in `docs/PROTOCOL.md` and `docs/SECURITY.md`.

## Diagrams

The PlantUML source files live in `docs/uml/` and are based on the implemented classes. Render these diagrams for the final report and add captions beneath each figure:

- **Figure 1. Ember use cases.** Students authenticate, message, manage groups and use Campus Pulse; moderator/admin actions are role-gated. Object authorization is included in operations that touch stored objects.
- **Figure 2. Messaging class diagram.** Shows `ClientController`, sender/receiver threads, `ClientStateModel`, `EmberServer.ConnectionHandler`, services, `StorageEngine`, `WalStorageEngine`, and `Message`.
- **Figure 3. Send → deliver → read sequence.** Follows the client command through storage, server fan-out, recipient model update, receipt, and sender status update.
- **Figure 4. Message and connection state diagrams.** Shows message delivery/expiry states and client disconnect/reconnect backoff states.
- **Figure 5. Login and image-upload activity.** Shows credential checking and the `ImageReader` header dimension check before image decode.

The class diagram is intentionally focused; the implementation is too large for a useful single all-classes diagram. Confirm the rendered diagrams still match the current source before submission.
