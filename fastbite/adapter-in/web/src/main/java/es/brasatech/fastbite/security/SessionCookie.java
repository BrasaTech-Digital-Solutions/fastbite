package es.brasatech.fastbite.security;

import es.brasatech.fastbite.application.platform.PlatformEnvironment;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The session cookie. The sandbox and production run side by side, often under the same domain,
 * and browsers share cookies across ports and subdomains: each environment therefore has its own
 * cookie name, so signing in to one never signs anyone out of the other.
 *
 * <p>The cookie is shared by the restaurant subdomains of the platform's domain, so an owner signs
 * in once. With {@code fastbite.session.cookie-domain} set (for example fastbite.brasatech.es), it is
 * shared with that domain and its subdomains only; other hosts, such as a restaurant's own domain,
 * get a cookie of their own. Without it, the last two parts of the host name are used, which suits
 * local development (kebab.localhost) but would share the cookie with every site of a company domain.
 *
 * <p>Built by {@link SecurityConfig} from {@code fastbite.environment} and the settings above.
 */
public class SessionCookie {

    static final String PRODUCTION_NAME = "FASTBITE_SESSION";
    static final String SANDBOX_NAME = "FASTBITE_SANDBOX_SESSION";
    /** The last two parts of the host name: kebab.localhost, or example.com for kebab.example.com. */
    static final String LAST_TWO_PARTS = "^(?:.+?\\.)?(\\w+\\.\\w+)$";

    private final String name;
    private final String domainPattern;

    public SessionCookie(PlatformEnvironment environment, String name, String domain) {
        this.name = name == null || name.isBlank() ? (environment.sandbox() ? SANDBOX_NAME : PRODUCTION_NAME) : name.strip();
        this.domainPattern = domain == null || domain.isBlank() ? LAST_TWO_PARTS : under(domain);
    }

    public String name() {
        return name;
    }

    /** For DefaultCookieSerializer: the domain to set is the first group; a host that does not match gets none. */
    public String domainPattern() {
        return domainPattern;
    }

    private static String under(String domain) {
        String base = domain.strip().toLowerCase(Locale.ROOT);
        if (base.startsWith(".")) {
            base = base.substring(1);
        }
        return "^(?:.+\\.)?(" + Pattern.quote(base) + ")$";
    }
}
