package es.brasatech.fastbite.application.verifactu;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Used until a provider (or a direct connection to the tax agency) is chosen: nothing is sent and
 * every record stays pending, safely stored with its fingerprint and chain. The adapter that
 * replaces it is marked {@code @Primary}.
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
    public List<Result> submit(VerifactuSystem system, List<Submission> records, boolean production) {
        return List.of();
    }
}
