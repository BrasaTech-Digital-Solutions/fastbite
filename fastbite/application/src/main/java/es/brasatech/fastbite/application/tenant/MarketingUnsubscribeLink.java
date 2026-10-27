package es.brasatech.fastbite.application.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Issues and verifies stateless HMAC-SHA256 unsubscribe tokens for commercial communications
 * (privacy policy §5, LSSI-CE art. 21, RGPD art. 7.3).
 *
 * <p>Derived from {@code fastbite.jobs.secret} ({@code FASTBITE_JOBS_SECRET}) with a domain-separation
 * prefix ({@link #PURPOSE}) so signatures can never collide with any other HMAC on the platform. If the
 * secret is blank, no links are issued and every token is refused (logging a warning once).
 *
 * <p>Future marketing mailings must carry the RFC 8058 one-click headers alongside the footer link:
 * <pre>
 * List-Unsubscribe: &lt;https://fastbite.brasatech.es/legal/unsubscribe?token=...&gt;
 * List-Unsubscribe-Post: List-Unsubscribe=One-Click
 * </pre>
 */
@Component
public class MarketingUnsubscribeLink {

    public static final String HEADER_LIST_UNSUBSCRIBE = "List-Unsubscribe";
    public static final String HEADER_LIST_UNSUBSCRIBE_POST = "List-Unsubscribe-Post";
    public static final String ONE_CLICK_BODY = "List-Unsubscribe=One-Click";

    private static final Logger log = LoggerFactory.getLogger(MarketingUnsubscribeLink.class);
    private static final String PURPOSE = "fastbite-marketing-unsubscribe\n";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final String secret;
    private final String publicUrl;
    private final AtomicBoolean warnedMissingSecret = new AtomicBoolean(false);

    public MarketingUnsubscribeLink(
            @Value("${fastbite.jobs.secret:}") String secret,
            @Value("${fastbite.public-url:http://localhost:8080}") String publicUrl) {
        this.secret = secret == null ? "" : secret.trim();
        String trimmedUrl = publicUrl == null ? "" : publicUrl.trim();
        this.publicUrl = trimmedUrl.endsWith("/") ? trimmedUrl.substring(0, trimmedUrl.length() - 1) : trimmedUrl;
    }

    /** Issues a signed token for {@code username}, or empty if {@code fastbite.jobs.secret} is blank. */
    public Optional<String> issueToken(String username) {
        if (!secretConfigured()) {
            return Optional.empty();
        }
        if (username == null) {
            throw new IllegalArgumentException("Username is required for an unsubscribe link");
        }
        String trimmed = username.trim();
        if (trimmed.isEmpty() || trimmed.contains("\n")) {
            throw new IllegalArgumentException("Username must be a single non-empty line");
        }
        String payload = ENCODER.encodeToString(trimmed.getBytes(StandardCharsets.UTF_8));
        String signature = ENCODER.encodeToString(hmac(payload));
        return Optional.of(payload + "." + signature);
    }

    /** Issues the full {@code /legal/unsubscribe?token=...} URL, or empty if the secret is blank. */
    public Optional<String> issueUrl(String username) {
        return issueToken(username).map(token -> publicUrl + "/legal/unsubscribe?token=" + token);
    }

    /** Verifies a token in constant time and returns the username, or empty if invalid or unconfigured. */
    public Optional<String> verify(String token) {
        if (!secretConfigured() || token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1 || token.indexOf('.', dot + 1) != -1) {
            return Optional.empty();
        }
        String payload = token.substring(0, dot);
        try {
            byte[] providedSignature = DECODER.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(hmac(payload), providedSignature)) {
                return Optional.empty();
            }
            String username = new String(DECODER.decode(payload), StandardCharsets.UTF_8);
            if (username.isBlank() || username.contains("\n") || !username.equals(username.trim())) {
                return Optional.empty();
            }
            return Optional.of(username);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private boolean secretConfigured() {
        if (secret.isEmpty()) {
            if (warnedMissingSecret.compareAndSet(false, true)) {
                log.warn("fastbite.jobs.secret is not set; marketing unsubscribe links cannot be issued or verified.");
            }
            return false;
        }
        return true;
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal((PURPOSE + payload).getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
