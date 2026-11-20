package es.brasatech.fastbite.application.digitalsign;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Startup guard preventing insecure Digital Sign configuration in real deployments.
 *
 * <ul>
 *   <li>When {@code fastbite.digitalsign.allow-insecure-local=true}, refuses to start if
 *       {@code base-url} is {@code https://}, if {@code base-url} points to a public host, or if
 *       {@code fastbite.public-url} starts with {@code https://}.</li>
 *   <li>When {@code fastbite.digitalsign.allow-insecure-local=false} and an API token is set,
 *       requires {@code https://} for {@code base-url}.</li>
 * </ul>
 */
@Component
public class DigitalSignSettingsGuard {

    public DigitalSignSettingsGuard(
            DigitalSignProperties properties,
            @Value("${fastbite.public-url:}") String publicUrl) {
        validate(properties, publicUrl);
    }

    public static void validate(DigitalSignProperties properties, String publicUrl) {
        if (properties == null) {
            throw new IllegalStateException("Digital Sign properties must not be null");
        }
        String rawBaseUrl = properties.normalizedBaseUrl();
        if (rawBaseUrl.isBlank()) {
            if (properties.isAllowInsecureLocal() || properties.isConfigured()) {
                throw new IllegalStateException("fastbite.digitalsign.base-url must not be blank");
            }
            return;
        }

        URI baseUri = parseUri(rawBaseUrl);
        String scheme = baseUri.getScheme() == null ? "" : baseUri.getScheme().toLowerCase(Locale.ROOT);
        String host = baseUri.getHost();
        if ((!"http".equals(scheme) && !"https".equals(scheme)) || host == null || host.isBlank()) {
            throw new IllegalStateException("fastbite.digitalsign.base-url must be a valid http or https URL");
        }

        if (properties.isAllowInsecureLocal()) {
            if ("https".equals(scheme)) {
                throw new IllegalStateException(
                        "fastbite.digitalsign.allow-insecure-local=true is forbidden with an https:// base-url");
            }
            if (!isLoopbackOrPrivateHost(host)) {
                throw new IllegalStateException(
                        "fastbite.digitalsign.allow-insecure-local=true is only allowed for loopback or private hosts, "
                                + "not '" + host + "'");
            }
            if (publicUrl != null && publicUrl.strip().toLowerCase(Locale.ROOT).startsWith("https://")) {
                throw new IllegalStateException(
                        "fastbite.digitalsign.allow-insecure-local=true is forbidden when fastbite.public-url uses https://");
            }
        } else if (properties.isConfigured() && !"https".equals(scheme)) {
            throw new IllegalStateException(
                    "fastbite.digitalsign.base-url must use https:// when an API token is configured "
                            + "(unless fastbite.digitalsign.allow-insecure-local=true for local mocks)");
        }
    }

    private static URI parseUri(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("fastbite.digitalsign.base-url is not a valid URI", e);
        }
    }

    static boolean isLoopbackOrPrivateHost(String host) {
        String h = host.strip().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if ("localhost".equals(h) || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) {
            return true;
        }
        if ("::1".equals(h) || "0:0:0:0:0:0:0:1".equals(h)) {
            return true;
        }
        if (h.contains(":")) {
            // IPv6 literal: allow unique local (fc00::/7) and link-local (fe80::/10) only.
            return isPrivateIpv6(h);
        }
        int[] ipv4 = parseIpv4(h);
        if (ipv4 != null) {
            return isLoopbackOrPrivateIpv4(ipv4);
        }
        // Single-label hostname (no dot), e.g. Docker Compose service name "digital-sign-mock".
        return !h.contains(".");
    }

    private static boolean isPrivateIpv6(String addr) {
        String firstSegment = addr.split(":", -1)[0];
        if (firstSegment.isEmpty()) {
            return false;
        }
        try {
            int value = Integer.parseInt(firstSegment, 16);
            return (value & 0xFE00) == 0xFC00 || (value & 0xFFC0) == 0xFE80;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int[] parseIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) {
                return null;
            }
            for (int j = 0; j < parts[i].length(); j++) {
                if (!Character.isDigit(parts[i].charAt(j))) {
                    return null;
                }
            }
            int v = Integer.parseInt(parts[i]);
            if (v < 0 || v > 255) {
                return null;
            }
            octets[i] = v;
        }
        return octets;
    }

    private static boolean isLoopbackOrPrivateIpv4(int[] ip) {
        // 127.0.0.0/8 loopback
        if (ip[0] == 127) {
            return true;
        }
        // 10.0.0.0/8 private
        if (ip[0] == 10) {
            return true;
        }
        // 172.16.0.0/12 private
        if (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31) {
            return true;
        }
        // 192.168.0.0/16 private
        if (ip[0] == 192 && ip[1] == 168) {
            return true;
        }
        // 169.254.0.0/16 link-local
        return ip[0] == 169 && ip[1] == 254;
    }
}
