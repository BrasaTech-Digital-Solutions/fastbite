package es.brasatech.fastbite.application.verifactu;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The producer's declaración responsable for this version of FastBite (Real Decreto 1007/2023 and
 * Orden HAC/1177/2024): the producer states that the billing software meets the regulation. It has
 * to be readable from the software itself, so the app shows it.
 *
 * <p>Until the producer has reviewed and signed it, {@link #signed()} is false and the app shows
 * it as a draft.
 */
@Component
public class VerifactuDeclaration {

    private final VerifactuSystem system;
    private final String producerAddress;
    private final String place;
    private final String date;
    private final boolean signed;

    public VerifactuDeclaration(VerifactuSystem system,
            @Value("${fastbite.verifactu.producer-address:}") String producerAddress,
            @Value("${fastbite.verifactu.declaration-place:}") String place,
            @Value("${fastbite.verifactu.declaration-date:}") String date,
            @Value("${fastbite.verifactu.declaration-signed:false}") boolean signed) {
        this.system = system;
        this.producerAddress = producerAddress;
        this.place = place;
        this.date = date;
        this.signed = signed;
    }

    public VerifactuSystem system() { return system; }

    public String producerAddress() { return producerAddress; }

    public String place() { return place; }

    public String date() { return date; }

    /** FastBite only works in VERI*FACTU mode: every record is sent to the tax agency. */
    public boolean onlyVerifactu() { return true; }

    /** One installation serves many businesses: each location is its own taxpayer and chain. */
    public boolean multipleTaxpayers() { return true; }

    /** Signed by the producer and published; otherwise the app shows it as a draft. */
    public boolean signed() { return signed && missing().isEmpty(); }

    /** The details still to fill in before it can be signed. */
    public List<String> missing() {
        List<String> missing = new ArrayList<>();
        if (blank(system.producerName())) missing.add("fastbite.verifactu.producer-name");
        if (blank(system.producerNif())) missing.add("fastbite.verifactu.producer-nif");
        if (blank(producerAddress)) missing.add("fastbite.verifactu.producer-address");
        if (blank(place)) missing.add("fastbite.verifactu.declaration-place");
        if (blank(date)) missing.add("fastbite.verifactu.declaration-date");
        return missing;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
