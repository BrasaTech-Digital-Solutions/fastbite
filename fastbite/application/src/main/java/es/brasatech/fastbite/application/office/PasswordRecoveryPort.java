package es.brasatech.fastbite.application.office;

import java.time.Instant;
import java.util.Optional;

public interface PasswordRecoveryPort {
    record Account(String id, String username, String tenantId) { }
    Optional<Account> enqueue(String identifier, String tenantId, Instant now);
    boolean valid(String hash, Instant now);
    Optional<Account> consume(String hash, String passwordHash, Instant now);
}
