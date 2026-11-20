package es.brasatech.fastbite.application.tenant;
import es.brasatech.fastbite.domain.tenant.SetupProgress;
import java.util.Optional;
import java.util.function.UnaryOperator;
public interface OwnerWorkspacePort {
    record OwnerSigner(String fullName, String verifiedEmail) {}

    SetupProgress read(String tenant);
    void update(String tenant, UnaryOperator<SetupProgress> change);
    default Optional<OwnerSigner> findOwnerSigner(String username) {
        return Optional.empty();
    }
}
