package es.brasatech.fastbite.service;

import es.brasatech.fastbite.application.table.TableSignatureUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Versioned, purpose-separated signatures. Rotating the table secret invalidates v1 links
 * unless the old key is retained when a future version is introduced. */
@Component
public class GuestTicketSignature {
    private final SecretKeySpec key;

    public GuestTicketSignature(@Value("${fastbite.table.secret:" + TableSignatureUtil.DEVELOPMENT_SECRET + "}") String secret) {
        this.key = new SecretKeySpec(hmac(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"),
                "fastbite/guest-ticket/v1".getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
    }

    public String sign(String tenant, String ticket) {
        String normalized = tenant.toLowerCase(Locale.ROOT);
        String input = normalized.length() + ":" + normalized + ticket.length() + ":" + ticket;
        return "v1." + HexFormat.of().formatHex(hmac(key, input.getBytes(StandardCharsets.UTF_8)));
    }

    public boolean valid(String tenant, String ticket, String signature) {
        if (tenant == null || tenant.isBlank() || ticket == null || signature == null) return false;
        return MessageDigest.isEqual(sign(tenant, ticket).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] hmac(SecretKeySpec key, byte[] input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign guest ticket", e);
        }
    }
}
