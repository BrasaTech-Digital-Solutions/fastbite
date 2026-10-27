package es.brasatech.fastbite.application.tenant;

import java.time.Instant;

/**
 * What the owner ticked at signup.
 *
 * @param termsVersion the version of the terms they accepted (the date the legal texts show)
 * @param marketing    whether they opted in to Brasatech's offers
 * @param at           when they submitted the form
 */
public record SignupConsents(String termsVersion, boolean marketing, Instant at) {

    public SignupConsents {
        if (termsVersion == null || termsVersion.isBlank() || at == null) {
            throw new IllegalArgumentException("Accept the terms of service to create your restaurant.");
        }
    }
}
