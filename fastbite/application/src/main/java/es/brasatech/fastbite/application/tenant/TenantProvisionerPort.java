package es.brasatech.fastbite.application.tenant;

public interface TenantProvisionerPort {
    void provisionTenant(String tenantId);

    /** The local demo restaurant ("kebab"), whose name is reserved so that nobody can register it. */
    default void provisionDemoTenant() {
        throw new UnsupportedOperationException("This provisioner cannot create the demo restaurant");
    }
}
