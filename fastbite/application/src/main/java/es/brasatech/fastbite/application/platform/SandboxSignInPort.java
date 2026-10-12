package es.brasatech.fastbite.application.platform;

import java.time.Instant;

/** The sandbox's record of the sign-in links it has accepted, so each one opens it only once. */
public interface SandboxSignInPort {

    /**
     * Records a link as used, and forgets links that have expired.
     *
     * @return false when the link was used before
     */
    boolean claim(String nonce, Instant expires, Instant now);
}
