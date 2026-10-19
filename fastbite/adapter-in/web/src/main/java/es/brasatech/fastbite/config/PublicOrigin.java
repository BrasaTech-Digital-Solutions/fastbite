package es.brasatech.fastbite.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Resolves the public web origin (scheme://host[:port]) for building absolute URLs.
 * <p>
 * On Cloud Run behind Firebase Hosting, the browser-facing host is never delivered to the app
 * (Cloud Run strips {@code X-Forwarded-Host}), so the request headers report an internal
 * {@code *.run.app} host. Therefore, on the server the only trustworthy origin is the
 * configured {@code fastbite.public-url}.
 * <p>
 * When {@code fastbite.public-url} is configured, it is always used as the origin.
 * The incoming request is used only as a fallback when the property is absent or blank
 * (local IDE runs and unit tests).
 * <p>
 * Note on custom restaurant domains: if individual restaurant domains are connected in the future,
 * Firebase Hosting / the edge gateway would need to deliver the requested host via an allowed header,
 * or the backend would need to resolve the custom domain from the tenant mapping.
 */
@Component
public class PublicOrigin {

    private final String publicUrl;

    public PublicOrigin(@Value("${fastbite.public-url:}") String publicUrl) {
        this.publicUrl = publicUrl != null && !publicUrl.isBlank() ? publicUrl.replaceAll("/+$", "") : null;
    }

    /**
     * Resolves the public origin from the configured public URL, or falls back to the request.
     */
    public String resolve(HttpServletRequest request) {
        if (publicUrl != null) {
            return publicUrl;
        }
        if (request == null) {
            return "";
        }
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int port = request.getServerPort();
        boolean standardPort = ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
        return standardPort ? (scheme + "://" + serverName) : (scheme + "://" + serverName + ":" + port);
    }

    /**
     * Checks whether the effective origin represents a local test address.
     */
    public boolean isLocal(HttpServletRequest request) {
        String origin = resolve(request);
        if (origin.isBlank()) {
            return true;
        }
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            if (host != null) {
                return host.startsWith("localhost") || host.startsWith("127.") || host.endsWith(".localhost");
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public String configuredPublicUrl() {
        return publicUrl;
    }
}
