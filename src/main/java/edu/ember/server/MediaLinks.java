package edu.ember.server;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signed links for pictures and videos. The server has already checked that the viewer may see a
 * post or story when it lists it, so it hands out a link that works for that one file until the
 * expiry and needs no login header. That lets the browser load media natively: in parallel, from
 * its HTTP cache, and with seeking for video, which a header-authenticated fetch cannot do.
 * The link never contains the session token or the viewer's name. The expiry is rounded to whole
 * days so the same link (and its cache entry) is reused all day.
 * @concept HMAC, immutable value objects, constant-time comparison
 */
final class MediaLinks {
    private static final long DAY_SECONDS = 86_400;
    private final byte[] key;

    /** A fresh random key: links from before a restart stop working (the website lists media again). */
    MediaLinks() {
        key = new byte[32];
        new SecureRandom().nextBytes(key);
    }

    /** /media/ID?v=f (full picture or the video) or v=t (thumbnail or video poster), with expiry and signature. */
    String link(String mediaId, boolean full, long nowMs) {
        long expiry = (nowMs / 1000 / DAY_SECONDS + 2) * DAY_SECONDS;
        String variant = full ? "f" : "t";
        return "/media/" + mediaId + "?v=" + variant + "&e=" + expiry + "&s=" + sign(mediaId, variant, expiry);
    }

    /** True when the signature matches and the link has not expired (or claims an impossible expiry). */
    boolean valid(String mediaId, String variant, String expiry, String signature, long nowMs) {
        long seconds;
        try {
            seconds = Long.parseLong(expiry);
        } catch (NumberFormatException exception) {
            return false;
        }
        long now = nowMs / 1000;
        if (seconds < now || seconds > now + 3 * DAY_SECONDS || !("f".equals(variant) || "t".equals(variant))) {
            return false;
        }
        return MessageDigest.isEqual(sign(mediaId, variant, seconds).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }

    private String sign(String mediaId, String variant, long expiry) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal((mediaId + "|" + variant + "|" + expiry).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 18));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 missing from the JDK", exception);
        }
    }
}
