package edu.ember.server;

import edu.ember.common.exceptions.RateLimitedException;
import edu.ember.common.exceptions.ValidationException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * One-time email codes (sign-up, password reset, linking an email) and email hashing. The server
 * never stores an address: only HMAC-SHA256(pepper, address), enough to find an account by email
 * and to refuse duplicates. Codes live in memory for 10 minutes, allow 5 guesses, and one email
 * gets at most one email a minute per purpose.
 * @concept maps, records, cryptographic hashing, encapsulation
 */
public final class EmailCodes {
    static final long CODE_TTL_MS = 10 * 60_000;
    static final long RESEND_GAP_MS = 60_000;
    static final int ATTEMPTS = 5;
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s.]+(\\.[^@\\s.]+)+");
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private record Pending(byte[] codeHash, long expiresMs, int attemptsLeft) { }

    private final SecureRandom random = new SecureRandom();
    private final byte[] pepper;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();

    public EmailCodes(byte[] pepper) {
        this.pepper = pepper.clone();
    }

    /** Trimmed, lower-case, checked address. */
    public static String normalize(String email) throws ValidationException {
        String clean = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (clean.length() > 254 || !EMAIL.matcher(clean).matches()) {
            throw new ValidationException("Enter a valid email address");
        }
        return clean;
    }

    /** The stored form of an address (keyed hash, base64url). */
    public String hash(String normalizedEmail) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(normalizedEmail.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** A fresh 6-digit code for this purpose and address; the previous one stops working. */
    public String issue(String purpose, String normalizedEmail, long nowMs) throws RateLimitedException {
        String emailHash = hash(normalizedEmail);
        String key = purpose + "|" + emailHash;
        Long last = lastSent.get(key);
        if (last != null && nowMs - last < RESEND_GAP_MS) {
            throw new RateLimitedException("Wait a minute before asking for another code");
        }
        lastSent.put(key, nowMs);
        String code = String.format("%06d", random.nextInt(1_000_000));
        pending.put(key, new Pending(sha256(code), nowMs + CODE_TTL_MS, ATTEMPTS));
        lastSent.entrySet().removeIf(entry -> nowMs - entry.getValue() > CODE_TTL_MS);
        pending.entrySet().removeIf(entry -> entry.getValue().expiresMs() < nowMs);
        return code;
    }

    /** True once for the right code; wrong guesses use up the attempts, then the code is gone. */
    public boolean verify(String purpose, String normalizedEmail, String code, long nowMs) {
        String key = purpose + "|" + hash(normalizedEmail);
        boolean[] ok = new boolean[1];
        pending.computeIfPresent(key, (ignored, entry) -> {
            if (entry.expiresMs() < nowMs || code == null) {
                return null;
            }
            if (MessageDigest.isEqual(entry.codeHash(), sha256(code.strip()))) {
                ok[0] = true;
                return null; // single use
            }
            return entry.attemptsLeft() > 1 ? new Pending(entry.codeHash(), entry.expiresMs(), entry.attemptsLeft() - 1) : null;
        });
        return ok[0];
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Random bytes for a new pepper. */
    static String newPepper() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return B64.encodeToString(bytes);
    }
}
