package es.brasatech.fastbite.application.office;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StaffAccountPort {
    record Staff(String id, String username, String fullName, String email, String pendingEmail, boolean verified) { }
    List<Staff> list(String actor, boolean owner, String tenant);
    Optional<Staff> find(String actor, boolean owner, String tenant, String id);
    Staff setPassword(String actor, boolean owner, String tenant, String id, String passwordHash);
    Staff setEmail(String actor, boolean owner, String tenant, String id, String email, String language, Instant now);
    Optional<Staff> enqueueReset(String actor, boolean owner, String tenant, String id, Instant now);
    boolean verifyEmail(String hash, Instant now);
}
