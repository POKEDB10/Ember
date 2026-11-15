package edu.ember.common.security;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Hashes passwords with salted PBKDF2-HMAC-SHA256. @concept cryptography, Strategy pattern */
public final class Pbkdf2PasswordHasher implements PasswordHasher {
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final char[] DUMMY_PASSWORD = "Ember fixed unknown account password".toCharArray();
    private static final byte[] DUMMY_SALT = {
        0x45, 0x6D, 0x62, 0x65, 0x72, 0x2D, 0x64, 0x75,
        0x6D, 0x6D, 0x79, 0x2D, 0x73, 0x61, 0x6C, 0x74
    };
    private final SecureRandom random = new SecureRandom();
    private volatile String cachedDummyHash;

    @Override
    public String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return encode(salt, derive(password, salt, ITERATIONS));
    }

    @Override
    public boolean verify(char[] password, String encodedHash) {
        ParsedHash parsed = parse(encodedHash);
        if (parsed == null) {
            return false;
        }
        byte[] candidate = derive(password, parsed.salt(), parsed.iterations());
        return MessageDigest.isEqual(parsed.hash(), candidate);
    }

    @Override
    public String dummyHash() {
        String current = cachedDummyHash;
        if (current == null) {
            synchronized (this) {
                current = cachedDummyHash;
                if (current == null) {
                    current = encode(DUMMY_SALT,
                            derive(DUMMY_PASSWORD, DUMMY_SALT, ITERATIONS));
                    cachedDummyHash = current;
                }
            }
        }
        return current;
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (Exception exception) {
            throw new IllegalStateException("JDK PBKDF2 support is unavailable", exception);
        } finally {
            spec.clearPassword();
        }
    }

    private static String encode(byte[] salt, byte[] hash) {
        return "$pbkdf2-sha256$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    private static ParsedHash parse(String encodedHash) {
        if (encodedHash == null) {
            return null;
        }
        String[] parts = encodedHash.split("\\$", -1);
        if (parts.length != 5 || !parts[1].equals("pbkdf2-sha256")) {
            return null;
        }
        try {
            int iterations = Integer.parseInt(parts[2]);
            byte[] salt = Base64.getDecoder().decode(parts[3]);
            byte[] hash = Base64.getDecoder().decode(parts[4]);
            if (iterations != ITERATIONS || salt.length != SALT_BYTES
                    || hash.length != HASH_BITS / 8) {
                return null;
            }
            return new ParsedHash(iterations, salt, hash);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record ParsedHash(int iterations, byte[] salt, byte[] hash) {
    }
}
