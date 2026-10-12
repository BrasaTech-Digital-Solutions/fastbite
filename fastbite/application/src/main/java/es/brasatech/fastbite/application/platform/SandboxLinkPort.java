package es.brasatech.fastbite.application.platform;

/** Production's calls to its sandbox. */
public interface SandboxLinkPort {

    /**
     * A location as production sets it up in the sandbox: its owner and the location itself.
     *
     * @param ownerName the owner's full name, shown in the sandbox
     */
    record LinkedLocation(String tenantId, String ownerUsername, String ownerName, String plan) {
    }

    /** The sandbox could not take it now: it is down, on another version during a release, or refused it. */
    class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    /** Sets the location up in the sandbox; doing it again changes nothing. */
    void provision(LinkedLocation location);
}
