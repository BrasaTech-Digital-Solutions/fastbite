package es.brasatech.fastbite.application.platform;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Signs what one environment sends the other, with the secret they share: HMAC-SHA256 of the time
 * and the content, sent as {@code t=<epoch seconds>,v1=<hex>}. The receiver refuses anything not
 * signed with the secret, or signed too long ago, so a captured request cannot be replayed later.
 */
public final class LinkSignature {

    /** How far apart the two clocks, and the request's travel, may be. */
    public static final Duration TOLERANCE = Duration.ofMinutes(5);

    private LinkSignature() {
    }

    public static String header(String secret, Instant now, byte[] content) {
        long time = now.getEpochSecond();
        return "t=" + time + ",v1=" + hmac(secret, time, content);
    }

    /** Whether the header signs this content with the secret, recently enough. */
    public static boolean verify(String secret, String header, byte[] content, Instant now) {
        if (secret == null || header == null) {
            return false;
        }
        long time = -1;
        String signature = null;
        for (String part : header.split(",")) {
            String[] pair = part.strip().split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            if (pair[0].equals("t")) {
                try {
                    time = Long.parseLong(pair[1]);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (pair[0].equals("v1")) {
                signature = pair[1];
            }
        }
        if (time < 0 || signature == null
                || Duration.between(Instant.ofEpochSecond(time), now).abs().compareTo(TOLERANCE) > 0) {
            return false;
        }
        return MessageDigest.isEqual(hmac(secret, time, content).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hmac(String secret, long time, byte[] content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((time + ".").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(mac.doFinal(content));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
