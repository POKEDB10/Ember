# Analysis and design

## Requirements

The implemented requirements include authenticated direct/group messaging, TLS 1.3, CRC-checked WAL recovery, server-side object checks, public profiles and follows, signature- and dimension-checked image uploads, public image posts, likes, comments, and feed/profile views. The wire protocol and current authorization matrix are recorded in `docs/PROTOCOL.md` and `docs/SECURITY.md`. Private accounts and blocking have not been implemented.

## Diagrams

Add the use-case, activity, state, sequence, and focused class diagrams from `docs/uml/*.puml` when they are generated in Phase 8. Each figure needs a caption and a short explanation. Keep class diagrams focused on at most about twelve classes each. The image-upload activity should show the header dimension check before pixel decode, and the social sequence should show server authorization before post/media access.
