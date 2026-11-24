package edu.ember.common.security;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * End-to-end encryption shared by the desktop app, byte for byte compatible with the website's
 * e2e.js and call.js: ECDH P-256 between devices, HKDF-SHA256, and AES-GCM.
 * <p>A chat message body is {@code e2e1.<senderDevice>.<salt>.<iv>.<ciphertext>.<dev>:<wrap>,...}.
 * A random message key encrypts the text (the conversation, sender and message id are bound in
 * as associated data); that key is then wrapped for each device with a key only the sending and
 * the receiving device can compute.
 * @concept cryptography, static utility class, immutable records
 */
public final class E2eCrypto {
    public static final String PREFIX = "e2e1.";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private static final byte[] ZERO_IV = new byte[12];

    /** One device's public key as handed out by the server. */
    public record DeviceKey(String userId, String deviceId, String publicKey, boolean active) { }

    /** This device's own key pair. */
    public record Identity(String deviceId, PrivateKey privateKey, String publicKey) { }

    private E2eCrypto() {
    }

    public static Identity newIdentity() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        KeyPair pair = generator.generateKeyPair();
        return new Identity(ENC.encodeToString(random(16)), pair.getPrivate(),
                ENC.encodeToString(rawPublicKey((ECPublicKey) pair.getPublic())));
    }

    /** Rebuilds an identity saved with {@link #privateKeyBytes}. */
    public static Identity loadIdentity(String deviceId, byte[] pkcs8, String publicKey)
            throws GeneralSecurityException {
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        return new Identity(deviceId, key, publicKey);
    }

    public static byte[] privateKeyBytes(Identity identity) {
        return identity.privateKey().getEncoded();
    }

    // ---- the account key backup: one key pair for all of an account's devices ----
    /** PBKDF2 rounds for the backup key (OWASP 2023 figure for PBKDF2-HMAC-SHA256). */
    public static final int BACKUP_ROUNDS = 600_000;
    public static final String BACKUP_PREFIX = "kb1.";

    /**
     * Seals the account key with the password: {@code kb1.<device>.<public>.<salt>.<iv>.<sealed PKCS#8>}.
     * The key is PBKDF2-SHA256(password, salt); account, device and public key are bound in as
     * associated data. Same format as e2e.js, so the website and the desktop open each other's backups.
     */
    public static String sealBackup(char[] password, String userId, Identity identity) throws GeneralSecurityException {
        byte[] salt = random(16);
        byte[] iv = random(12);
        byte[] sealed = aesGcm(true, backupKey(password, salt), iv, backupData(userId, identity.deviceId(),
                identity.publicKey()), privateKeyBytes(identity));
        return BACKUP_PREFIX + String.join(".", identity.deviceId(), identity.publicKey(), encode(salt), encode(iv),
                encode(sealed));
    }

    /** Opens a backup; throws when the password is wrong or the backup was changed. */
    public static Identity openBackup(char[] password, String userId, String backup) throws GeneralSecurityException {
        String[] parts = backup == null || !backup.startsWith(BACKUP_PREFIX) ? new String[0]
                : backup.substring(BACKUP_PREFIX.length()).split("\\.");
        if (parts.length != 5) {
            throw new GeneralSecurityException("Not a key backup");
        }
        byte[] pkcs8 = aesGcm(false, backupKey(password, decode(parts[2])), decode(parts[3]),
                backupData(userId, parts[0], parts[1]), decode(parts[4]));
        return loadIdentity(parts[0], pkcs8, parts[1]);
    }

    /** The device id inside a backup (null when it is not one), without opening it. */
    public static String backupDeviceId(String backup) {
        return backup == null || !backup.startsWith(BACKUP_PREFIX) ? null
                : backup.substring(BACKUP_PREFIX.length()).split("\\.")[0];
    }

    private static byte[] backupKey(char[] password, byte[] salt) throws GeneralSecurityException {
        var spec = new javax.crypto.spec.PBEKeySpec(password, salt, BACKUP_ROUNDS, 256);
        try {
            return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] backupData(String userId, String deviceId, String publicKey) {
        return ("ember-keybackup-v1|" + userId + "|" + deviceId + "|" + publicKey).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    public static String encode(byte[] bytes) {
        return ENC.encodeToString(bytes);
    }

    public static byte[] decode(String text) {
        return DEC.decode(text);
    }

    /** 65-byte uncompressed point: 0x04, X, Y (the WebCrypto "raw" format). */
    public static byte[] rawPublicKey(ECPublicKey key) {
        byte[] raw = new byte[65];
        raw[0] = 4;
        copyUnsigned(key.getW().getAffineX(), raw, 1);
        copyUnsigned(key.getW().getAffineY(), raw, 33);
        return raw;
    }

    public static PublicKey publicKey(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 65 || raw[0] != 4) {
            throw new GeneralSecurityException("Not an uncompressed P-256 key");
        }
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec curve = parameters.getParameterSpec(ECParameterSpec.class);
        ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, curve));
    }

    /** ECDH shared secret: the 32-byte x coordinate, as WebCrypto's deriveBits(256) returns. */
    public static byte[] ecdh(PrivateKey mine, byte[] theirRaw) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(mine);
        agreement.doPhase(publicKey(theirRaw), true);
        return agreement.generateSecret();
    }

    /** HKDF-SHA256 (RFC 5869) for up to 32 bytes of output. */
    public static byte[] hkdf(byte[] inputKey, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
        byte[] pseudoRandomKey = mac.doFinal(inputKey);
        mac.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1);
        return Arrays.copyOf(mac.doFinal(), length);
    }

    public static byte[] aesGcm(boolean encrypt, byte[] key, byte[] iv, byte[] associatedData, byte[] input)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, iv));
        if (associatedData != null) {
            cipher.updateAAD(associatedData);
        }
        return cipher.doFinal(input);
    }

    private static byte[] associatedData(String conversationId, String sender, String messageId) {
        return (conversationId + "\n" + sender + "\n" + messageId).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] wrapKey(Identity me, byte[] otherRaw, byte[] salt, String from, String to)
            throws GeneralSecurityException {
        return hkdf(ecdh(me.privateKey(), otherRaw), salt,
                ("ember-msg-v1|" + from + "|" + to).getBytes(StandardCharsets.UTF_8), 32);
    }

    /** Encrypts a message for every given device (this device is added if missing). */
    public static String seal(Identity me, String conversationId, String sender, String messageId,
            String text, List<DeviceKey> devices) throws GeneralSecurityException {
        byte[] messageKey = random(32);
        byte[] iv = random(12);
        byte[] salt = random(16);
        byte[] ciphertext = aesGcm(true, messageKey, iv, associatedData(conversationId, sender, messageId),
                text.getBytes(StandardCharsets.UTF_8));
        byte[] bind = binding(conversationId, sender, messageId, iv, ciphertext);
        StringBuilder wraps = new StringBuilder();
        boolean self = false;
        for (DeviceKey device : devices) {
            self |= device.deviceId().equals(me.deviceId());
            appendWrap(wraps, me, device.deviceId(), device.publicKey(), salt, messageKey, bind);
        }
        if (!self) {
            appendWrap(wraps, me, me.deviceId(), me.publicKey(), salt, messageKey, bind);
        }
        return PREFIX + me.deviceId() + "." + encode(salt) + "." + encode(iv) + "." + encode(ciphertext)
                + "." + wraps;
    }

    /**
     * What each key wrap is bound to: SHA-256 of chat, sender, message id, iv and ciphertext. Another
     * recipient who learns the message key still cannot pass new text off under the sender's wrap.
     */
    private static byte[] binding(String conversationId, String sender, String messageId, byte[] iv,
            byte[] ciphertext) throws GeneralSecurityException {
        java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
        sha.update(associatedData(conversationId, sender, messageId));
        sha.update(iv);
        return sha.digest(ciphertext);
    }

    private static void appendWrap(StringBuilder wraps, Identity me, String deviceId, String publicKey,
            byte[] salt, byte[] messageKey, byte[] bind) throws GeneralSecurityException {
        byte[] wrapped = aesGcm(true, wrapKey(me, decode(publicKey), salt, me.deviceId(), deviceId),
                ZERO_IV, bind, messageKey);
        if (wraps.length() > 0) {
            wraps.append(',');
        }
        wraps.append(deviceId).append(':').append(encode(wrapped));
    }

    /**
     * Decrypts a message sent to this device. {@code senderDevices} are the sender's registered
     * keys; a message from any other device is refused, so nobody can pose as the sender.
     */
    public static String open(Identity me, String conversationId, String sender, String messageId,
            String body, List<DeviceKey> senderDevices) throws GeneralSecurityException {
        String[] parts = body.substring(PREFIX.length()).split("\\.");
        if (parts.length != 5) {
            throw new GeneralSecurityException("Malformed encrypted message");
        }
        String from = parts[0];
        DeviceKey senderKey = senderDevices.stream()
                .filter(key -> key.userId().equals(sender) && key.deviceId().equals(from))
                .findFirst().orElseThrow(() -> new GeneralSecurityException("Unknown sender device"));
        String mine = null;
        for (String wrap : parts[4].split(",")) {
            if (wrap.startsWith(me.deviceId() + ":")) {
                mine = wrap.substring(me.deviceId().length() + 1);
            }
        }
        if (mine == null) {
            throw new GeneralSecurityException("Not encrypted for this device");
        }
        byte[] bind = binding(conversationId, sender, messageId, decode(parts[2]), decode(parts[3]));
        byte[] messageKey = aesGcm(false, wrapKey(me, decode(senderKey.publicKey()), decode(parts[1]), from,
                me.deviceId()), ZERO_IV, bind, decode(mine));
        return new String(aesGcm(false, messageKey, decode(parts[2]),
                associatedData(conversationId, sender, messageId), decode(parts[3])), StandardCharsets.UTF_8);
    }

    private static void copyUnsigned(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        int start = Math.max(0, bytes.length - 32);
        int length = bytes.length - start;
        System.arraycopy(bytes, start, target, offset + 32 - length, length);
    }
}
