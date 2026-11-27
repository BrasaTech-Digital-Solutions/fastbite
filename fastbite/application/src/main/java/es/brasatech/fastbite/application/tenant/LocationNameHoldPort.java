package es.brasatech.fastbite.application.tenant;

import java.time.Instant;
import java.util.Optional;

/**
 * Tracks temporary slug reservations for deleted production locations so printed table QRs
 * cannot land on a stranger's restaurant. Only the original owner may reuse a held slug,
 * which ends the hold.
 */
public interface LocationNameHoldPort {

    record NameHold(
            String slug,
            String owner,
            Instant heldUntil,
            Instant createdAt) {
    }

    void hold(String slug, String owner, Instant heldUntil, Instant now);

    Optional<NameHold> findActive(String slug, Instant now);

    void release(String slug);

    int purgeExpired(Instant now);
}
