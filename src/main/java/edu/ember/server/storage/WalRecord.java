package edu.ember.server.storage;

/** Package-private decoded WAL record; bytes are checksum-verified before construction. @concept package access, records */
record WalRecord(byte type, byte[] payload) {
}
