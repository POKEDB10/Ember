# Analysis and design

## Requirements

The implemented requirements include authenticated direct/group messaging, TLS 1.3, CRC-checked WAL recovery and GZIP snapshot rotation, server-side object checks, private profiles and follow approval, blocking and reports, signature- and dimension-checked image uploads, posts, stories, likes, comments, group polls, and in-chat search. The wire protocol and authorization matrix are recorded in `docs/PROTOCOL.md` and `docs/SECURITY.md`.

## Diagrams

Add the use-case, activity, state, sequence, and focused class diagrams from `docs/uml/*.puml` when they are generated in Phase 8. Each figure needs a caption and a short explanation. Keep class diagrams focused on at most about twelve classes each. The image-upload activity should show the header dimension check before pixel decode, and the social sequence should show server authorization before post/media access.
