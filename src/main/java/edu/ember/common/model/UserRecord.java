package edu.ember.common.model;

/** Immutable account data held by the authentication service. @concept immutable objects, records */
public record UserRecord(String userId, String username, String displayName, String passwordHash) {
}
