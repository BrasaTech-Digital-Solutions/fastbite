package es.brasatech.fastbite.application.verifactu;

import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationGate.HoldReason;

import java.util.Objects;

/**
 * Thrown when a production location attempts to create an order, take payment, or issue a fiscal
 * ticket/record without an active VERI*FACTU authorisation and platform collaboration switch.
 */
public class VerifactuAuthorizationRequiredException extends RuntimeException {

    public static final String ERROR_CODE = "VERIFACTU_AUTHORIZATION_REQUIRED";
    public static final String DEFAULT_MESSAGE = "This location cannot issue tickets until its VERI*FACTU authorisation is active.";

    private final HoldReason reason;

    public VerifactuAuthorizationRequiredException(HoldReason reason) {
        super(DEFAULT_MESSAGE);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public HoldReason reason() {
        return reason;
    }

    public String code() {
        return ERROR_CODE;
    }
}
