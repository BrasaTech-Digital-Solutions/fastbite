package es.brasatech.fastbite.security;

import es.brasatech.fastbite.application.platform.PlatformEnvironment;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The session cookie. The sandbox and production run side by side, often under the same domain,
 * and browsers share cookies across ports and subdomains: each environment therefore has its own
 * cookie name, so signing in to one never signs anyone out of the other.
 *
 * <p>By default, session cookies are host-only (no {@code Domain} attribute is set), preventing
 * cookie leakage across sibling subdomains or parent domains (e.g. sandbox.fastbite.brasatech.es
 * and fastbite.brasatech.es). When {@code fastbite.session.cookie-domain} is configured (e.g.
 * {@code fastbite.brasatech.es}), it is shared with that domain and its subdomains only; other hosts,
 * such as a restaurant's own custom domain, keep a cookie of their own.
 *
 * <p>Built by {@link SecurityConfig} from {@code fastbite.environment} and the settings above.
 */
public class SessionCookie {

    static final String PRODUCTION_NAME = "FASTBITE_SESSION";
    static final String SANDBOX_NAME = "FASTBITE_SANDBOX_SESSION";

    private final String name;
    private final String domainPattern;

    public SessionCookie(PlatformEnvironment environment, String name, String domain) {
        this.name = name == null || name.isBlank() ? (environment.sandbox() ? SANDBOX_NAME : PRODUCTION_NAME) : name.strip();
        this.domainPattern = domain == null || domain.isBlank() ? null : under(domain);
    }

    public String name() {
        return name;
    }

    /** For DefaultCookieSerializer: the domain pattern to match, or empty if host-only. */
    public Optional<String> domainPattern() {
        return Optional.ofNullable(domainPattern);
    }

    private static String under(String domain) {
        String base = domain.strip().toLowerCase(Locale.ROOT);
        if (base.startsWith(".")) {
            base = base.substring(1);
        }
        return "^(?:.+\\.)?(" + Pattern.quote(base) + ")$";
    }
}
