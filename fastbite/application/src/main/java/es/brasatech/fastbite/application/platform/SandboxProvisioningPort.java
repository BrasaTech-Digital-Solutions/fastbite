package es.brasatech.fastbite.application.platform;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Production's list of locations still to be set up in the sandbox, kept until the sandbox has them. */
public interface SandboxProvisioningPort {

    /**
     * How long a worker holds an entry while sending it to the sandbox. Must exceed
     * HttpSandboxLink's 5s connect timeout plus 20s read timeout (25s total) so a slow
     * or cold sandbox never suffers a duplicate attempt mid-flight.
     */
    Duration CLAIM_LEASE = Duration.ofSeconds(60);

    enum State { PENDING, DONE }

    /**
     * @param ownerName the owner's full name as production has it now
     * @param lastError why the last attempt failed, if it did
     */
    record Entry(String tenantId, String ownerUsername, String ownerName, String plan, State state, int attempts,
            String lastError, Instant nextAttempt) {
    }

    /** Adds a location to set up; a location already listed keeps its entry. */
    void enqueue(String tenantId, String ownerUsername, String plan, Instant now);

    /**
     * Atomically claims a pending entry for the caller, so only one worker sends it.
     *
     * @return true if claimed; false if another worker holds an active lease
     */
    boolean claim(String tenantId, Instant now);

    /** Locations whose next attempt is due, oldest first. */
    List<Entry> due(Instant now, int limit);

    Optional<Entry> find(String tenantId);

    void done(String tenantId, Instant now);

    void failed(String tenantId, String error, Instant nextAttempt);
}
