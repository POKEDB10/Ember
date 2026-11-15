package edu.ember.server;

import edu.ember.common.exceptions.AuthenticationException;
import edu.ember.common.exceptions.ValidationException;
import edu.ember.common.model.UserRecord;
import edu.ember.common.security.InputValidator;
import edu.ember.common.security.PasswordHasher;
import edu.ember.server.storage.StorageEngine;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Registers accounts and verifies passwords using the configured hasher. @concept service layer, Strategy pattern */
public final class AuthService {
    private static final String LOGIN_ERROR = "Invalid username or password";
    private final StorageEngine storage;
    private final PasswordHasher passwordHasher;

    public AuthService(StorageEngine storage, PasswordHasher passwordHasher) {
        this.storage = storage;
        this.passwordHasher = passwordHasher;
    }

    public UserRecord register(String username, char[] password, String displayName)
            throws IOException, ValidationException, AuthenticationException {
        InputValidator.validateUsername(username);
        char[] checkedPassword = InputValidator.validatePassword(password);
        if (displayName == null || displayName.isBlank() || displayName.length() > 40) {
            throw new ValidationException("Display name must be 1–40 characters");
        }
        if (storage.findUser(username).isPresent()) {
            throw new AuthenticationException(LOGIN_ERROR);
        }
        String normalized = username.toLowerCase(Locale.ROOT);
        UserRecord user = new UserRecord(normalized, normalized, displayName,
                passwordHasher.hash(checkedPassword));
        storage.saveUser(user);
        return user;
    }

    public UserRecord login(String username, char[] password)
            throws IOException, ValidationException, AuthenticationException {
        InputValidator.validateUsername(username);
        char[] checkedPassword = InputValidator.validatePassword(password);
        Optional<UserRecord> user = storage.findUser(username);
        String hash = user.map(UserRecord::passwordHash).orElseGet(passwordHasher::dummyHash);
        boolean validPassword = passwordHasher.verify(checkedPassword, hash);
        if (user.isEmpty() || !validPassword) {
            throw new AuthenticationException(LOGIN_ERROR);
        }
        return user.get();
    }
}
