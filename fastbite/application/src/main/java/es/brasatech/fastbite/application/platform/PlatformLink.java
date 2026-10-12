package es.brasatech.fastbite.application.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The link between production and the sandbox. Production is the front door (signup, subscription,
 * the owner's account) and sets up every new location in the sandbox too; the sandbox trusts only
 * production's signed sign-in links. Each environment knows the other's address and the secret they
 * share.
 *
 * <p>Without a link an environment runs standalone, as before the link existed: the sandbox has its
 * own signup and production applies onboarding concepts to its own locations. A production
 * environment must be linked: it refuses to start standalone unless {@code fastbite.link.standalone}
 * allows it on purpose (an IDE run, tests, a demo).
 */
@Component
public class PlatformLink {

    /** Long enough to sign links that cannot be guessed. */
    static final int MIN_SECRET_LENGTH = 32;

    private final PlatformEnvironment environment;
    private final String url;
    private final String internalUrl;
    private final String secret;
    private final boolean standalone;

    public PlatformLink(PlatformEnvironment environment,
            @Value("${fastbite.link.url:}") String url,
            @Value("${fastbite.link.internal-url:}") String internalUrl,
            @Value("${fastbite.link.secret:}") String secret,
            @Value("${fastbite.link.standalone:false}") boolean standalone) {
        this.environment = environment;
        this.url = trimmed(url);
        this.internalUrl = trimmed(internalUrl) != null ? trimmed(internalUrl) : this.url;
        this.secret = secret == null || secret.isBlank() ? null : secret;
        this.standalone = standalone;
        if (this.url != null && (this.secret == null || this.secret.length() < MIN_SECRET_LENGTH)) {
            throw new IllegalStateException("FASTBITE_LINK_SECRET must be set, with at least " + MIN_SECRET_LENGTH
                    + " characters, when FASTBITE_LINK_URL is set");
        }
        if (this.url == null && this.secret != null) {
            throw new IllegalStateException("FASTBITE_LINK_SECRET is set but FASTBITE_LINK_URL is not");
        }
        if (environment.production() && this.url == null && !standalone) {
            throw new IllegalStateException("Production must be linked to its sandbox: set FASTBITE_LINK_URL (the "
                    + "sandbox's address) and FASTBITE_LINK_SECRET, or set FASTBITE_STANDALONE=true to run "
                    + "production on its own on purpose");
        }
    }

    /** Linked to the other environment; standalone otherwise. */
    public boolean linked() {
        return url != null;
    }

    /** The other environment's address for browsers: the sandbox from production, production from the sandbox. */
    public String url() {
        return url;
    }

    /** The other environment's address for calls between the servers; the public one unless set apart. */
    public String internalUrl() {
        return internalUrl;
    }

    /** Signs the links and calls between the two environments. */
    public String secret() {
        return secret;
    }

    /** A production environment that is not linked to a sandbox. */
    public boolean standaloneProduction() {
        return environment.production() && !linked();
    }

    private static String trimmed(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
