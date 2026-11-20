package es.brasatech.fastbite.application.digitalsign;

import java.time.Instant;

/**
 * Per-call execution options for {@link DigitalSignClient}, allowing callers such as the
 * synchronous webhook handler to enforce a strict overall deadline and disable retries.
 *
 * @param deadline   optional absolute deadline by which the call must finish ({@code null} = no overall deadline)
 * @param maxRetries maximum number of retries on 5xx/network errors ({@code < 0} = use configured default)
 */
public record CallOptions(Instant deadline, int maxRetries) {

    public static final CallOptions DEFAULT = new CallOptions(null, -1);

    public static CallOptions withDeadlineNoRetries(Instant deadline) {
        return new CallOptions(deadline, 0);
    }

    public static CallOptions withDeadline(Instant deadline) {
        return new CallOptions(deadline, -1);
    }
}
