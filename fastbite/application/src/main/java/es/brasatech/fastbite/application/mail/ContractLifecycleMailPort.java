package es.brasatech.fastbite.application.mail;

import java.time.Instant;

/**
 * Service notifications about a restaurant location's contract lifecycle (terms §5 & §13).
 * Sent regardless of marketing consent, without a marketing footer.
 */
public interface ContractLifecycleMailPort {

    void sendOrdersPausedNotice(String email, String tenantId, Instant pausedSince, Instant contractEndsAt);

    void sendContractEndingWarning(String email, String tenantId, Instant contractEndsAt);

    void sendContractEndedNotice(String email, String tenantId, Instant contractEndedAt, Instant deletionAt);
}
