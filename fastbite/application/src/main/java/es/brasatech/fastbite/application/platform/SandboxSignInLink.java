package es.brasatech.fastbite.application.platform;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * The link that takes an owner from production into the sandbox without a password. Production signs
 * who enters, which location they land on, its version, until when the link is good and a random
 * number the sandbox accepts only once: {@code <payload>.<signature>}, both base64url, the signature
 * HMAC-SHA256 with the shared secret. A link is good for {@link #LIFETIME}.
 */
public final class SandboxSignInLink {

    public static final Duration LIFETIME = Duration.ofSeconds(60);

    /** Keeps a sign-in signature from ever passing for another message signed with the same secret. */
    private static final String PURPOSE = "fastbite-sandbox-sign-in\n";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What a link says: who enters which location, from which version, until when; once. */
    public record Ticket(String owner, String tenantId, String version, Instant expires, String nonce) {
    }

    private SandboxSignInLink() {
    }

    public static String issue(String secret, String owner, String tenantId, String version, Instant now) {
        byte[] nonce = new byte[18];
        RANDOM.nextBytes(nonce);
        return sign(secret, new Ticket(owner, tenantId, version, now.plus(LIFETIME), ENCODER.encodeToString(nonce)));
    }

    static String sign(String secret, Ticket ticket) {
        for (String field : new String[] {ticket.owner(), ticket.tenantId(), ticket.version(), ticket.nonce()}) {
            if (field == null || field.isEmpty() || field.contains("\n")) {
                throw new IllegalArgumentException("A sandbox sign-in link needs every field, on one line each");
            }
        }
        String payload = ENCODER.encodeToString(String.join("\n", ticket.owner(), ticket.tenantId(), ticket.version(),
                Long.toString(ticket.expires().getEpochSecond()), ticket.nonce()).getBytes(StandardCharsets.UTF_8));
        return payload + "." + ENCODER.encodeToString(hmac(secret, payload));
    }

    /** The link's ticket when it is signed with the secret and still good; empty otherwise. */
    public static Optional<Ticket> read(String secret, String token, Instant now) {
        if (secret == null || token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String payload = token.substring(0, dot);
        try {
            if (!MessageDigest.isEqual(hmac(secret, payload), DECODER.decode(token.substring(dot + 1)))) {
                return Optional.empty();
            }
            String[] fields = new String(DECODER.decode(payload), StandardCharsets.UTF_8).split("\n", -1);
            if (fields.length != 5) {
                return Optional.empty();
            }
            Instant expires = Instant.ofEpochSecond(Long.parseLong(fields[3]));
            // Expired, or good for longer than production ever issues, even with the clocks apart
            if (!now.isBefore(expires) || expires.isAfter(now.plus(LIFETIME).plus(LinkSignature.TOLERANCE))) {
                return Optional.empty();
            }
            return Optional.of(new Ticket(fields[0], fields[1], fields[2], expires, fields[4]));
        } catch (IllegalArgumentException e) {
            // Not base64, or not a number where the time goes
            return Optional.empty();
        }
    }

    private static byte[] hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal((PURPOSE + payload).getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
