package es.brasatech.fastbite.domain.tenant;

/**
 * Thrown when an operation cannot proceed immediately because the tenant's provisioning
 * or setup is already in progress on another concurrent thread or instance.
 */
public class TenantProvisioningInProgressException extends RuntimeException {
    public TenantProvisioningInProgressException(String message) {
        super(message);
    }
}
