package es.brasatech.fastbite.application.tenant;

/** Told when a location has been created, for work that follows it, such as setting it up in the sandbox. */
public interface NewLocationListener {

    NewLocationListener NONE = (tenantId, ownerUsername, plan) -> { };

    /** Must not fail the creation: anything that can fail is done later and retried. */
    void locationCreated(String tenantId, String ownerUsername, String plan);
}
