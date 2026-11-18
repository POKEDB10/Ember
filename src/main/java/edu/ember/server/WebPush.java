package edu.ember.server;

import edu.ember.common.model.PushSubscription;
import edu.ember.server.storage.StorageEngine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Sends Web Push notifications, so a phone or browser hears about a message even when the
 * site is closed. Payloads are encrypted for each browser (RFC 8291, aes128gcm) and the server
 * proves its identity with a VAPID token (RFC 8292). Only the JDK is used.
 * @concept elliptic-curve cryptography, HTTP client, asynchronous programming
 */
final class WebPush {
    private static final Logger LOGGER = Logger.getLogger(WebPush.class.getName());
    /** Real browser push services. Anything else is refused, so clients cannot make us call
     * arbitrary internal addresses (SSRF). */
    private static final List<String> PUSH_HOSTS = List.of(".googleapis.com", ".mozilla.com",
            ".mozaws.net", ".notify.windows.com", ".push.apple.com");
    static final Predicate<URI> KNOWN_PUSH_SERVICES = uri -> "https".equals(uri.getScheme())
            && uri.getHost() != null && PUSH_HOSTS.stream().anyMatch(suffix ->
                    uri.getHost().endsWith(suffix) || uri.getHost().equals(suffix.substring(1)));
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder UNB64URL = Base64.getUrlDecoder();
    private final StorageEngine storage;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final SecureRandom random = new SecureRandom();
    private final ECParameterSpec curve;
    private final PrivateKey vapidPrivate;
    private final byte[] vapidPublic;
    private volatile Predicate<URI> allowedEndpoint = KNOWN_PUSH_SERVICES;
    private volatile String contact = "https://github.com/";

    /** Loads the server's VAPID key pair from storage, or creates and stores one. */
    WebPush(StorageEngine storage) throws IOException, GeneralSecurityException {
        this.storage = storage;
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        curve = parameters.getParameterSpec(ECParameterSpec.class);
        var savedPrivate = storage.setting("vapid.private");
        var savedPublic = storage.setting("vapid.public");
        if (savedPrivate.isPresent() && savedPublic.isPresent()) {
            vapidPrivate = KeyFactory.getInstance("EC").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(savedPrivate.get())));
            vapidPublic = UNB64URL.decode(savedPublic.get());
        } else {
            KeyPair pair = newKeyPair();
            vapidPrivate = pair.getPrivate();
            vapidPublic = raw((ECPublicKey) pair.getPublic());
            storage.saveSetting("vapid.private", Base64.getEncoder().encodeToString(
                    vapidPrivate.getEncoded()));
            storage.saveSetting("vapid.public", B64URL.encodeToString(vapidPublic));
        }
    }

    /** The key browsers need to subscribe (base64url, uncompressed P-256 point). */
    String publicKey() {
        return B64URL.encodeToString(vapidPublic);
    }

    /** Tests point the server at a local endpoint; production keeps the allowlist. */
    void setAllowedEndpoint(Predicate<URI> filter) {
        allowedEndpoint = filter;
    }

    /** The site address, sent as the VAPID contact (push services want a URL or mailto). */
    void setContact(String httpsOrigin) {
        contact = httpsOrigin;
    }

    /** Checks a subscription coming from a client before it is stored. */
    boolean accepts(String endpoint, String p256dh, String auth) {
        try {
            URI uri = URI.create(endpoint);
            byte[] key = UNB64URL.decode(p256dh);
            return endpoint.length() <= 2048 && allowedEndpoint.test(uri) && key.length == 65
                    && key[0] == 4 && UNB64URL.decode(auth).length == 16;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** Encrypts and posts one notification without blocking; dead subscriptions are removed. */
    void send(PushSubscription subscription, String payloadJson) {
        try {
            URI endpoint = URI.create(subscription.endpoint());
            if (!allowedEndpoint.test(endpoint)) {
                return;
            }
            byte[] body = encrypt(UNB64URL.decode(subscription.p256dh()),
                    UNB64URL.decode(subscription.auth()), payloadJson.getBytes(StandardCharsets.UTF_8));
            String audience = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20))
                    .header("TTL", "86400").header("Urgency", "high")
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("Authorization", "vapid t=" + vapidToken(audience) + ", k=" + publicKey())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
                if (response != null && (response.statusCode() == 404 || response.statusCode() == 410)) {
                    try {
                        storage.savePushSubscription(subscription, false); // the browser unsubscribed
                    } catch (IOException exception) {
                        LOGGER.log(Level.FINE, "Could not drop expired subscription", exception);
                    }
                } else if (error != null || response.statusCode() / 100 != 2) {
                    LOGGER.log(Level.FINE, "Push delivery failed: "
                            + (error != null ? error : "HTTP " + response.statusCode()));
                }
            });
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            LOGGER.log(Level.FINE, "Push not sent", exception);
        }
    }

    /** RFC 8291 "aes128gcm": one record, ephemeral ECDH key, HKDF-derived key and nonce. */
    byte[] encrypt(byte[] browserPublic, byte[] authSecret, byte[] plaintext)
            throws GeneralSecurityException {
        KeyPair ephemeral = newKeyPair();
        byte[] serverPublic = raw((ECPublicKey) ephemeral.getPublic());
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(ephemeral.getPrivate());
        agreement.doPhase(publicKey(browserPublic), true);
        byte[] sharedSecret = agreement.generateSecret();
        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII),
                browserPublic, serverPublic);
        byte[] ikm = Arrays.copyOf(hmac(hmac(authSecret, sharedSecret), concat(keyInfo, new byte[] {1})), 32);
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] prk = hmac(salt, ikm);
        byte[] contentKey = Arrays.copyOf(hmac(prk, concat(
                "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat(
                "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(contentKey, "AES"),
                new GCMParameterSpec(128, nonce));
        byte[] sealed = cipher.doFinal(concat(plaintext, new byte[] {2})); // 2 = last record
        ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + serverPublic.length);
        header.put(salt).putInt(4096).put((byte) serverPublic.length).put(serverPublic);
        return concat(header.array(), sealed);
    }

    /** VAPID: a short-lived ES256 JWT naming the push service it is meant for. */
    private String vapidToken(String audience) throws GeneralSecurityException {
        String header = B64URL.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}"
                .getBytes(StandardCharsets.UTF_8));
        String claims = B64URL.encodeToString(("{\"aud\":" + edu.ember.common.util.MiniJson.quote(audience)
                + ",\"exp\":" + (System.currentTimeMillis() / 1000 + 12 * 3600)
                + ",\"sub\":" + edu.ember.common.util.MiniJson.quote(contact) + "}")
                .getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(vapidPrivate);
        signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        return header + "." + claims + "." + B64URL.encodeToString(signer.sign());
    }

    private KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), random);
        return generator.generateKeyPair();
    }

    private ECPublicKey publicKey(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 65 || raw[0] != 4) {
            throw new GeneralSecurityException("Not an uncompressed P-256 point");
        }
        ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, curve));
    }

    /** Uncompressed point: 0x04, then X and Y as 32-byte big-endian numbers. */
    static byte[] raw(ECPublicKey key) {
        return concat(new byte[] {4}, fixed32(key.getW().getAffineX()), fixed32(key.getW().getAffineY()));
    }

    private static byte[] fixed32(BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] result = new byte[32];
        int copy = Math.min(32, bytes.length);
        System.arraycopy(bytes, bytes.length - copy, result, 32 - copy, copy);
        return result;
    }

    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            joined.writeBytes(part);
        }
        return joined.toByteArray();
    }
}
