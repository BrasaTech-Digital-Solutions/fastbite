package es.brasatech.fastbite.application.digitalsign;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Verifies the {@code DigitalSign-Signature} header ({@code t=<unix seconds>,v1=<hex>})
 * against the raw webhook request body bytes using HMAC-SHA256 and constant-time comparison.
 */
@Component
public class DigitalSignWebhookVerifier {

    public static final String SIGNATURE_HEADER = "DigitalSign-Signature";
    public static final String EVENT_ID_HEADER = "DigitalSign-Event-Id";
    public static final Duration MAX_SKEW = Duration.ofMinutes(5);

    private final DigitalSignProperties properties;
    private final Clock clock;

    public DigitalSignWebhookVerifier(DigitalSignProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public boolean verify(String signatureHeader, byte[] rawBody) {
        String secret = properties == null ? null : properties.getWebhookSecret();
        return verify(secret, signatureHeader, rawBody, clock.instant());
    }

    /**
     * Verifies {@code DigitalSign-Signature} on raw body bytes at the given instant.
     *
     * @return {@code true} only when the header is well-formed, within 5 minutes of {@code now},
     *         and matches {@code hex(HMAC-SHA256(secret UTF-8, t + "." + rawBody))} in constant time.
     */
    public static boolean verify(String secret, String signatureHeader, byte[] rawBody, Instant now) {
        if (secret == null || secret.isBlank() || signatureHeader == null || signatureHeader.isBlank()
                || rawBody == null || now == null) {
            return false;
        }
        Long timestamp = null;
        String providedV1 = null;
        for (String part : signatureHeader.split(",")) {
            String trimmed = part.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] kv = trimmed.split("=", 2);
            if (kv.length != 2) {
                return false;
            }
            String key = kv[0].strip();
            String value = kv[1].strip();
            if ("t".equals(key)) {
                if (timestamp != null || value.isEmpty()) {
                    return false;
                }
                try {
                    timestamp = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if ("v1".equals(key)) {
                if (providedV1 != null || value.isEmpty()) {
                    return false;
                }
                providedV1 = value;
            }
        }
        if (timestamp == null || timestamp < 0 || providedV1 == null) {
            return false;
        }
        try {
            Duration skew = Duration.between(Instant.ofEpochSecond(timestamp), now).abs();
            if (skew.compareTo(MAX_SKEW) > 0) {
                return false;
            }
        } catch (DateTimeException | ArithmeticException e) {
            return false;
        }
        String expected = hmacHex(secret, timestamp, rawBody);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                providedV1.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    /** Computes the {@code DigitalSign-Signature} header value for the given secret, epoch second, and raw body. */
    public static String signHeader(String secret, long epochSeconds, byte[] rawBody) {
        return "t=" + epochSeconds + ",v1=" + hmacHex(secret, epochSeconds, rawBody);
    }

    static String hmacHex(String secret, long epochSeconds, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((epochSeconds + ".").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
