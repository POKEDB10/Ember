package edu.ember.server;

import edu.ember.common.exceptions.AuthenticationException;
import edu.ember.common.exceptions.AuthenticationLockedException;
import edu.ember.common.exceptions.RateLimitedException;
import edu.ember.common.exceptions.ValidationException;
import edu.ember.common.model.UserRecord;
import edu.ember.common.model.UserProfile;
import edu.ember.common.security.InputValidator;
import edu.ember.common.security.PasswordHasher;
import edu.ember.server.storage.StorageEngine;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Semaphore;

/** Registers accounts and verifies passwords using the configured hasher. @concept service layer, Strategy pattern */
public final class AuthService {
    private static final String LOGIN_ERROR = "Invalid username or password";
    private final StorageEngine storage;
    private final PasswordHasher passwordHasher;
    private final UserLockManager locks;
    private final SecurityAuditLogger auditLogger;
    private final Semaphore hashSlots = new Semaphore(4);

    public AuthService(StorageEngine storage, PasswordHasher passwordHasher) {
        this(storage, passwordHasher, new UserLockManager(), null);
    }

    public AuthService(StorageEngine storage, PasswordHasher passwordHasher,
            UserLockManager locks, SecurityAuditLogger auditLogger) {
        this.storage = storage;
        this.passwordHasher = passwordHasher;
        this.locks = locks;
        this.auditLogger = auditLogger;
    }

    public UserRecord register(String username, char[] password, String displayName)
            throws IOException, ValidationException, AuthenticationException, RateLimitedException {
        InputValidator.validateUsername(username);
        char[] checkedPassword = InputValidator.validatePassword(password);
        if (displayName == null || displayName.isBlank() || displayName.length() > 40) {
            throw new ValidationException("Display name must be 1–40 characters");
        }
        if (storage.findUser(username).isPresent()) {
            throw new AuthenticationException(LOGIN_ERROR);
        }
        String normalized = username.toLowerCase(Locale.ROOT);
        String passwordHash;
        acquireHashSlot();
        try {
            passwordHash = passwordHasher.hash(checkedPassword);
        } finally {
            hashSlots.release();
        }
        UserRecord user = new UserRecord(normalized, normalized, displayName, passwordHash);
        storage.saveUser(user);
        storage.saveProfile(new UserProfile(user.userId(), displayName, "", ""));
        return user;
    }

    public UserRecord login(String username, char[] password)
            throws IOException, ValidationException, AuthenticationException, RateLimitedException {
        InputValidator.validateUsername(username);
        char[] checkedPassword = InputValidator.validatePassword(password);
        if (locks.isLocked(username)) {
            log("authn_login_lock", username, "username");
            throw new AuthenticationLockedException(LOGIN_ERROR);
        }
        Optional<UserRecord> user = storage.findUser(username);
        String hash = user.map(UserRecord::passwordHash).orElseGet(passwordHasher::dummyHash);
        boolean validPassword;
        acquireHashSlot();
        try {
            validPassword = passwordHasher.verify(checkedPassword, hash);
        } finally {
            hashSlots.release();
        }
        if (user.isEmpty() || !validPassword) {
            boolean newlyLocked = locks.recordFailure(username);
            log("authn_login_fail", username, "password");
            if (newlyLocked) {
                log("authn_login_lock", username, "username");
            }
            throw new AuthenticationException(LOGIN_ERROR);
        }
        locks.recordSuccess(username);
        log("authn_login_success", username, "password");
        return user.get();
    }

    /** An email nobody uses: spends the same hashing time as a real check, then fails the same way. */
    public void failLogin(char[] password) throws AuthenticationException, RateLimitedException {
        acquireHashSlot();
        try {
            passwordHasher.verify(password, passwordHasher.dummyHash());
        } finally {
            hashSlots.release();
        }
        throw new AuthenticationException(LOGIN_ERROR);
    }

    /** Sets a new password after the owner proved their email with a one-time code. */
    public UserRecord resetPassword(String username, char[] newPassword)
            throws IOException, ValidationException, AuthenticationException, RateLimitedException {
        char[] checkedPassword = InputValidator.validatePassword(newPassword);
        UserRecord user = storage.findUser(username).orElseThrow(() -> new AuthenticationException(LOGIN_ERROR));
        String passwordHash;
        acquireHashSlot();
        try {
            passwordHash = passwordHasher.hash(checkedPassword);
        } finally {
            hashSlots.release();
        }
        UserRecord updated = new UserRecord(user.userId(), user.username(), user.displayName(), passwordHash);
        storage.updateUser(updated);
        locks.unlock(username);
        log("authn_password_reset", username, "email_code");
        return updated;
    }

    public boolean unlock(String username) { return locks.unlock(username); }

    public java.util.List<String> lockedUsers() { return locks.lockedUsers(); }

    private void acquireHashSlot() throws RateLimitedException {
        try {
            hashSlots.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RateLimitedException("Password verification is busy");
        }
    }

    private void log(String event, String username, String source) throws IOException {
        if (auditLogger != null) {
            auditLogger.log(event, username, source);
        }
    }
}
