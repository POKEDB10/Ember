package edu.ember.common.security;

/** Defines the password hashing and verification strategy. @concept interfaces, Strategy pattern */
public interface PasswordHasher {
    String hash(char[] password);

    boolean verify(char[] password, String encodedHash);

    String dummyHash();
}
