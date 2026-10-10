package es.brasatech.fastbite.application.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Which FastBite environment this deployment is. The same image runs as the sandbox, where new
 * restaurants learn and test, and as production, where they trade for real.
 *
 * <p>The sandbox is the default: a deployment that forgets to say it is production issues training
 * tickets that are visibly not valid, instead of real invoices made from test data.
 */
@Component
public class PlatformEnvironment {

    public enum Kind { SANDBOX, PRODUCTION }

    private final Kind kind;

    public PlatformEnvironment(@Value("${fastbite.environment:sandbox}") String kind) {
        try {
            this.kind = Kind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("fastbite.environment must be sandbox or production, not " + kind);
        }
    }

    public Kind kind() {
        return kind;
    }

    public boolean sandbox() {
        return kind == Kind.SANDBOX;
    }

    public boolean production() {
        return kind == Kind.PRODUCTION;
    }
}
