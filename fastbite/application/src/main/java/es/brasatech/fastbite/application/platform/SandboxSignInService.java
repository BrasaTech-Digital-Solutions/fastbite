package es.brasatech.fastbite.application.platform;

import es.brasatech.fastbite.application.tenant.TenantLocationService;
import es.brasatech.fastbite.application.tenant.TenantRegistrationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;

/**
 * The sandbox's side of "Enter sandbox": it takes production's signed link, once, and says which owner
 * to sign in and where they land. A linked sandbox is entered only this way, never with a password.
 */
@Service
public class SandboxSignInService {

    private static final Logger log = LoggerFactory.getLogger(SandboxSignInService.class);

    public enum Refusal {
        /** Not signed by production, expired, or used before. */
        INVALID,
        /** Signed by production on another version, during a release. */
        OTHER_VERSION,
        /** The sandbox does not have this owner's location (yet). */
        NOT_READY
    }

    /** Either an owner to sign in and the location they land on, or why not. */
    public record Outcome(String owner, String tenantId, Refusal refusal) {
        public boolean accepted() {
            return refusal == null;
        }

        static Outcome refused(Refusal refusal) {
            return new Outcome(null, null, refusal);
        }
    }

    private final PlatformEnvironment environment;
    private final PlatformLink link;
    private final SandboxSignInPort signIns;
    private final TenantLocationService locations;
    private final TenantRegistrationPort registration;
    private final String version;
    private final Clock clock;

    @Autowired
    public SandboxSignInService(PlatformEnvironment environment, PlatformLink link, SandboxSignInPort signIns,
            TenantLocationService locations, TenantRegistrationPort registration,
            @Value("${fastbite.version:dev}") String version) {
        this(environment, link, signIns, locations, registration, version, Clock.systemUTC());
    }

    SandboxSignInService(PlatformEnvironment environment, PlatformLink link, SandboxSignInPort signIns,
            TenantLocationService locations, TenantRegistrationPort registration, String version, Clock clock) {
        this.environment = environment;
        this.link = link;
        this.signIns = signIns;
        this.locations = locations;
        this.registration = registration;
        this.version = version;
        this.clock = clock;
    }

    /** Whether this environment is a sandbox entered from production. */
    public boolean active() {
        return environment.sandbox() && link.linked();
    }

    public Outcome accept(String token) {
        if (!active()) {
            return Outcome.refused(Refusal.INVALID);
        }
        var now = clock.instant();
        Optional<SandboxSignInLink.Ticket> read = SandboxSignInLink.read(link.secret(), token, now);
        if (read.isEmpty()) {
            log.warn("Refused a sandbox sign-in link that is not signed by production, or expired");
            return Outcome.refused(Refusal.INVALID);
        }
        var ticket = read.get();
        if (!version.equals(ticket.version())) {
            return Outcome.refused(Refusal.OTHER_VERSION);
        }
        if (!signIns.claim(ticket.nonce(), ticket.expires(), now)) {
            log.warn("Refused a sandbox sign-in link for {} that was already used", ticket.owner());
            return Outcome.refused(Refusal.INVALID);
        }
        if (!locations.isOwnerOf(ticket.owner(), ticket.tenantId()) || !activeOwner(ticket.owner())) {
            return Outcome.refused(Refusal.NOT_READY);
        }
        return new Outcome(ticket.owner(), ticket.tenantId(), null);
    }

    private boolean activeOwner(String owner) {
        try {
            registration.requireActiveOwner(owner);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
