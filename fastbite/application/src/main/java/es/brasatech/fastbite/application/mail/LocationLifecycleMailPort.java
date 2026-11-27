package es.brasatech.fastbite.application.mail;

import java.time.Instant;

/**
 * Service notice sent once to the owner after a restaurant location is deleted or closed.
 * Does not require marketing opt-in and never includes secrets or auth-bypass links.
 */
public interface LocationLifecycleMailPort {

    enum Action {
        DELETED,
        CLOSED
    }

    enum Scope {
        SANDBOX,
        PRODUCTION,
        BOTH
    }

    void sendLocationLifecycleNotice(
            String ownerEmail,
            String tenantId,
            Action action,
            Scope scope,
            Instant when,
            String language);

    default void sendLocationLifecycleNotice(
            String ownerEmail,
            String tenantId,
            Action action,
            Scope scope,
            Instant when,
            Instant undoUntil,
            String language) {
        sendLocationLifecycleNotice(ownerEmail, tenantId, action, scope, when, language);
    }
}
