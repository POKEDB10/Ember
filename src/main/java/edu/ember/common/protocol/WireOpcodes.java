package edu.ember.common.protocol;

/** Frozen command and event opcode values from the Ember protocol. @concept constants, encapsulation */
public final class WireOpcodes {
    public static final int CMD_AUTH_REGISTER = 0x01;
    public static final int CMD_AUTH_LOGIN = 0x02;
    public static final int CMD_AUTH_RESUME = 0x03;
    public static final int CMD_AUTH_LOGOUT = 0x04;
    public static final int CMD_PING = 0x05;
    public static final int CMD_MSG_SEND = 0x10;
    public static final int CMD_MSG_RECEIPT = 0x11;
    public static final int CMD_MSG_REACT = 0x12;
    public static final int CMD_MSG_EDIT = 0x13;
    public static final int CMD_MSG_DELETE = 0x14;
    public static final int CMD_MSG_PIN = 0x15;
    public static final int CMD_MSG_STAR = 0x16;
    public static final int CMD_TYPING = 0x17;
    public static final int CMD_POLL_CREATE = 0x18;
    public static final int CMD_POLL_VOTE = 0x19;
    public static final int CMD_SYNC_PULL = 0x1A;
    public static final int CMD_HISTORY_PULL = 0x1B;
    public static final int CMD_GROUP_CREATE = 0x20;
    public static final int CMD_GROUP_MEMBER = 0x21;
    public static final int CMD_USER_SEARCH = 0x22;
    public static final int CMD_GROUP_INFO = 0x23;
    public static final int CMD_STATUS_SET = 0x24;
    public static final int CMD_PUSH_SUBSCRIBE = 0x25;
    /** Call signalling: ring, accept, decline, end, sdp, ice, key, relay. */
    public static final int CMD_CALL = 0x26;
    /** One encrypted call packet; the payload is opaque bytes, forwarded unread. */
    public static final int CMD_CALL_MEDIA = 0x27;
    public static final int CMD_PROFILE_UPDATE = 0x30;
    public static final int CMD_FOLLOW_ACTION = 0x31;
    public static final int CMD_MEDIA_UPLOAD = 0x32;
    public static final int CMD_POST_CREATE = 0x33;
    public static final int CMD_POST_INTERACT = 0x34;
    public static final int CMD_STORY_CREATE = 0x35;
    public static final int CMD_STORY_VIEW = 0x36;
    public static final int CMD_USER_BLOCK = 0x37;
    public static final int CMD_MOD_REPORT = 0x38;
    public static final int CMD_ADMIN_ACTION = 0x39;
    public static final int CMD_FEED_PULL = 0x3A;
    public static final int CMD_PROFILE_GET = 0x3B;
    public static final int CMD_MEDIA_GET = 0x3C;
    public static final int EVT_AUTH_OK = 0x40;
    public static final int EVT_PONG = 0x41;
    public static final int EVT_MSG_ACK = 0x42;
    public static final int EVT_MSG_DELIVER = 0x43;
    public static final int EVT_RECEIPT_UPDATE = 0x44;
    public static final int EVT_MSG_UPDATE = 0x45;
    public static final int EVT_TYPING = 0x46;
    public static final int EVT_PRESENCE_UPDATE = 0x47;
    public static final int EVT_SYNC_ITEM = 0x48;
    public static final int EVT_SYNC_DONE = 0x49;
    public static final int EVT_HISTORY_PAGE = 0x4A;
    public static final int EVT_GROUP_UPDATE = 0x4B;
    public static final int EVT_USER_SEARCH_RESULT = 0x4C;
    public static final int EVT_MEDIA_OK = 0x50;
    public static final int EVT_MEDIA_DATA = 0x51;
    public static final int EVT_FEED_PAGE = 0x52;
    public static final int EVT_PROFILE_DATA = 0x53;
    public static final int EVT_NOTIFY = 0x54;
    public static final int EVT_STORY_UPDATE = 0x55;
    public static final int EVT_ADMIN_DATA = 0x56;
    public static final int EVT_POLL_UPDATE = 0x57;
    public static final int EVT_CALL = 0x58;
    public static final int EVT_CALL_MEDIA = 0x59;
    public static final int EVT_ERROR = 0x70;

    private WireOpcodes() {
        throw new AssertionError("No instances");
    }

    public static boolean isCommand(int opcode) {
        return opcode >= 0x01 && opcode <= 0x3F;
    }

    public static boolean isEvent(int opcode) {
        return (opcode >= 0x40 && opcode <= 0x6F) || opcode == EVT_ERROR;
    }
}
