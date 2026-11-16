# Ember wire protocol

Frozen in Phase 0. Frames use an 18-byte big-endian header: `MAGIC` int `0x454D4252`, version byte `0x01`, opcode byte, correlation ID long, payload length int, then payload bytes. Length is checked before allocation. Text payloads are UTF-8 key/value lines; media commands use the binary layout specified below.

## Payload encoding

KV payloads contain one `key=value` pair per line. Split at the first `=`. Escape backslash as `\\`, newline as `\n`, and carriage return as `\r`. Keys match `[a-zA-Z0-9_.]{1,32}`. Lists use indexed keys and a count such as `opt.n=2`. Unknown fields are ignored; missing required fields are bad requests. `CMD_MEDIA_UPLOAD` uses `[4-byte metaLen][KV metadata][raw image bytes]`.

## Frozen opcodes

| Hex | Name | Direction | Key fields |
|---|---|---|---|
| 01 | CMD_AUTH_REGISTER | C→S | user, pass, displayName |
| 02 | CMD_AUTH_LOGIN | C→S | user, pass |
| 03 | CMD_AUTH_RESUME | C→S | token, lastGlobalSeq |
| 04 | CMD_AUTH_LOGOUT | C→S | — |
| 05 | CMD_PING | C→S | ts |
| 10 | CMD_MSG_SEND | C→S | clientMsgId, convId, body, replyToId, ttlSec |
| 11 | CMD_MSG_RECEIPT | C→S | convId, clientMsgId, status |
| 12 | CMD_MSG_REACT | C→S | clientMsgId, emoji, remove |
| 13 | CMD_MSG_EDIT | C→S | clientMsgId, newBody |
| 14 | CMD_MSG_DELETE | C→S | clientMsgId |
| 15 | CMD_MSG_PIN | C→S | convId, clientMsgId, pinned |
| 16 | CMD_MSG_STAR | C→S | clientMsgId, starred |
| 17 | CMD_TYPING | C→S | convId |
| 18 | CMD_POLL_CREATE | C→S | groupId, question, opt.n, opt.i |
| 19 | CMD_POLL_VOTE | C→S | pollMsgId, optionIdx |
| 1A | CMD_SYNC_PULL | C→S | sinceGlobalSeq |
| 1B | CMD_HISTORY_PULL | C→S | convId, beforeSeq, limit |
| 20 | CMD_GROUP_CREATE | C→S | name, desc, member.n, member.i |
| 21 | CMD_GROUP_MEMBER | C→S | groupId, targetUser, action |
| 22 | CMD_USER_SEARCH | C→S | prefix |
| 23 | CMD_GROUP_INFO | C→S | groupId |
| 30 | CMD_PROFILE_UPDATE | C→S | bio, isPrivate, avatarMediaId |
| 31 | CMD_FOLLOW_ACTION | C→S | targetUser, action |
| 32 | CMD_MEDIA_UPLOAD | C→S | binary upload payload |
| 33 | CMD_POST_CREATE | C→S | mediaId, caption |
| 34 | CMD_POST_INTERACT | C→S | postId, type, commentText |
| 35 | CMD_STORY_CREATE | C→S | mediaId, caption |
| 36 | CMD_STORY_VIEW | C→S | no fields (request visible stories) |
| 37 | CMD_USER_BLOCK | C→S | targetUser, blocked |
| 38 | CMD_MOD_REPORT | C→S | targetId, reason |
| 39 | CMD_ADMIN_ACTION | C→S | action, targetId |
| 3A | CMD_FEED_PULL | C→S | beforeTs, limit |
| 3B | CMD_PROFILE_GET | C→S | targetUser |
| 3C | CMD_MEDIA_GET | C→S | mediaId, variant |
| 40 | EVT_AUTH_OK | S→C | token, userId, role, globalSeq |
| 41 | EVT_PONG | S→C | ts |
| 42 | EVT_MSG_ACK | S→C | clientMsgId, convSeq, globalSeq, serverTs |
| 43 | EVT_MSG_DELIVER | S→C | full message fields |
| 44 | EVT_RECEIPT_UPDATE | S→C | clientMsgId, status, delivered, read, total |
| 45 | EVT_MSG_UPDATE | S→C | reaction/edit/delete/pin/poll/expiry changes |
| 46 | EVT_TYPING | S→C | convId, userId |
| 47 | EVT_PRESENCE_UPDATE | S→C | userId, state, lastSeen |
| 48 | EVT_SYNC_ITEM | S→C | one missed event with globalSeq |
| 49 | EVT_SYNC_DONE | S→C | upToGlobalSeq |
| 4A | EVT_HISTORY_PAGE | S→C | messages |
| 4B | EVT_GROUP_UPDATE | S→C | group fields |
| 4C | EVT_USER_SEARCH_RESULT | S→C | user.n, user.i |
| 50 | EVT_MEDIA_OK | S→C | mediaId, thumbnail bytes |
| 51 | EVT_MEDIA_DATA | S→C | binary image |
| 52 | EVT_FEED_PAGE | S→C | posts and stories |
| 53 | EVT_PROFILE_DATA | S→C | profile fields |
| 54 | EVT_NOTIFY | S→C | follow request, like, comment, story view |
| 55 | EVT_STORY_UPDATE | S→C | story added/expired |
| 56 | EVT_ADMIN_DATA | S→C | reports, audit rows |
| 57 | EVT_POLL_UPDATE | S→C | pollId, groupId, options, vote totals |
| 70 | EVT_ERROR | S→C | code, message |

Opcodes `0x01–0x3F` are commands, `0x40–0x6F` are server events, and `0x70` is the error event. New opcodes must remain in those ranges. Error codes are 400, 401, 403, 404, 409, 413, 415, 423, 429, and 500. Error wording must not reveal whether a username exists.

## Frame limits

Client-to-server frames are limited to 4 KiB before authentication and 64 KiB afterward, except media upload at 5 MiB plus metadata. Server-to-client frames are limited to 64 KiB, except media responses at 5 MiB plus metadata. Both endpoints enforce limits before allocating payload buffers.
