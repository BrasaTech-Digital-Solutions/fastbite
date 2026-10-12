package es.brasatech.fastbite.application.verifactu;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Used when no adapter to the tax agency is present: nothing is sent and every record stays
 * pending, safely stored with its fingerprint and chain. The adapter that replaces it is marked
 * {@code @Primary}.
 */
@Component
public class UnconfiguredVerifactuSubmitter implements VerifactuSubmitter {

    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public String name() {
        return null;
    }

    @Override
    public List<Result> submit(VerifactuSystem system, Installation installation, List<Submission> records,
            boolean production) {
        return List.of();
    }
}
