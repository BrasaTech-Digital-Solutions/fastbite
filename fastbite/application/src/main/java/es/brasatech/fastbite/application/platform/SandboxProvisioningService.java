package es.brasatech.fastbite.application.platform;

import es.brasatech.fastbite.application.tenant.NewLocationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Sets up in the sandbox every location production creates, so new owners practise there. A
 * location is only listed when it is created; the sandbox is called afterwards, and again until it
 * has it, so production never fails because its sandbox is down or briefly on another version.
 *
 * <p>Only a linked production does this; standalone environments and the sandbox itself do not.
 */
@Service
public class SandboxProvisioningService implements NewLocationListener {

    private static final Logger log = LoggerFactory.getLogger(SandboxProvisioningService.class);

    /** Attempts handled per run of the job. */
    static final int BATCH = 20;
    private static final Duration FIRST_RETRY = Duration.ofSeconds(30);
    private static final Duration LONGEST_WAIT = Duration.ofMinutes(30);

    private final SandboxProvisioningPort queue;
    private final SandboxLinkPort sandbox;
    private final PlatformEnvironment environment;
    private final PlatformLink link;
    private final Clock clock;

    @Autowired
    public SandboxProvisioningService(SandboxProvisioningPort queue, SandboxLinkPort sandbox,
            PlatformEnvironment environment, PlatformLink link) {
        this(queue, sandbox, environment, link, Clock.systemUTC());
    }

    SandboxProvisioningService(SandboxProvisioningPort queue, SandboxLinkPort sandbox, PlatformEnvironment environment,
            PlatformLink link, Clock clock) {
        this.queue = queue;
        this.sandbox = sandbox;
        this.environment = environment;
        this.link = link;
        this.clock = clock;
    }

    /** Whether this environment sets its locations up in a sandbox. */
    public boolean active() {
        return environment.production() && link.linked();
    }

    @Override
    public void locationCreated(String tenantId, String ownerUsername, String plan) {
        if (!active()) {
            return;
        }
        try {
            queue.enqueue(tenantId, ownerUsername, plan, clock.instant());
        } catch (RuntimeException e) {
            // The location exists; a missing practice space must not undo it
            log.error("Location {} could not be listed for its sandbox: {}", tenantId, e.getMessage());
        }
    }

    /**
     * Calls the sandbox for every location due, and keeps those it could not take for later.
     *
     * @return how many the sandbox has now
     */
    public int sendDue() {
        if (!active()) {
            return 0;
        }
        int done = 0;
        for (SandboxProvisioningPort.Entry entry : queue.due(clock.instant(), BATCH)) {
            if (send(entry)) {
                done++;
            }
        }
        return done;
    }

    /**
     * Makes sure the sandbox has the location now, for an owner about to enter it.
     *
     * @return true when the sandbox has it; false when it is still being prepared
     */
    public boolean ensure(String tenantId) {
        if (!active()) {
            return false;
        }
        Optional<SandboxProvisioningPort.Entry> entry = queue.find(tenantId);
        if (entry.isEmpty()) {
            return false;
        }
        return entry.get().state() == SandboxProvisioningPort.State.DONE || send(entry.get());
    }

    /** Where the location's practice space stands; empty when this environment has no sandbox or never listed it. */
    public Optional<SandboxProvisioningPort.Entry> status(String tenantId) {
        return active() ? queue.find(tenantId) : Optional.empty();
    }

    private boolean send(SandboxProvisioningPort.Entry entry) {
        try {
            sandbox.provision(new SandboxLinkPort.LinkedLocation(entry.tenantId(), entry.ownerUsername(),
                    entry.ownerName(), entry.plan()));
            queue.done(entry.tenantId(), clock.instant());
            log.info("Location {} is set up in the sandbox", entry.tenantId());
            return true;
        } catch (RuntimeException e) {
            Instant next = clock.instant().plus(wait(entry.attempts()));
            queue.failed(entry.tenantId(), e.getMessage(), next);
            log.warn("Location {} could not be set up in the sandbox yet (attempt {}), trying again at {}: {}",
                    entry.tenantId(), entry.attempts() + 1, next, e.getMessage());
            return false;
        }
    }

    /** 30 seconds after the first failure, doubling up to half an hour. */
    static Duration wait(int attemptsBefore) {
        Duration wait = FIRST_RETRY.multipliedBy(1L << Math.min(attemptsBefore, 10));
        return wait.compareTo(LONGEST_WAIT) > 0 ? LONGEST_WAIT : wait;
    }
}
