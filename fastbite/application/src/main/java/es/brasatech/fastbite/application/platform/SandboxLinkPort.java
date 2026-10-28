package es.brasatech.fastbite.application.platform;

import java.io.IOException;
import java.io.InputStream;

/** Production's calls to its sandbox. */
public interface SandboxLinkPort {

    /** Default bundle size limit (200 MiB). */
    long DEFAULT_MAX_BUNDLE_BYTES = 200L * 1024 * 1024;

    /**
     * A location as production sets it up in the sandbox: its owner and the location itself.
     *
     * @param ownerName the owner's full name, shown in the sandbox
     */
    record LinkedLocation(String tenantId, String ownerUsername, String ownerName, String plan) {
    }

    enum Reason { UNREACHABLE, OTHER_VERSION, NOTHING_TO_PROMOTE, TOO_LARGE, TIMED_OUT, REFUSED }

    /** The sandbox could not take it now: it is down, on another version during a release, or refused it. */
    class Unavailable extends RuntimeException {
        private final Reason reason;

        public Unavailable(String message) {
            this(Reason.UNREACHABLE, message);
        }

        public Unavailable(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Unavailable(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** Thrown when streaming a bundle fails during read due to timeout, size limit or other stream errors. */
    class BundleStreamException extends IOException {
        private final Reason reason;

        public BundleStreamException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public BundleStreamException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    /** Sets the location up in the sandbox; doing it again changes nothing. */
    void provision(LinkedLocation location);

    /**
     * Streams the sandbox location's production bundle.
     * The returned InputStream is bounded by maxBytes and protected by a watchdog deadline;
     * the caller must close it.
     */
    InputStream streamBundle(String tenantId, String ownerUsername, long maxBytes);

    /**
     * Streams the sandbox location's production bundle using the default 200 MiB limit.
     */
    default InputStream streamBundle(String tenantId, String ownerUsername) {
        return streamBundle(tenantId, ownerUsername, DEFAULT_MAX_BUNDLE_BYTES);
    }
}
