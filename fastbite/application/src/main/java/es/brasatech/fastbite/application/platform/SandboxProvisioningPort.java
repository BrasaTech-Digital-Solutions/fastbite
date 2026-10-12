package es.brasatech.fastbite.application.platform;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Production's list of locations still to be set up in the sandbox, kept until the sandbox has them. */
public interface SandboxProvisioningPort {

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

    /** Locations whose next attempt is due, oldest first. */
    List<Entry> due(Instant now, int limit);

    Optional<Entry> find(String tenantId);

    void done(String tenantId, Instant now);

    void failed(String tenantId, String error, Instant nextAttempt);
}
