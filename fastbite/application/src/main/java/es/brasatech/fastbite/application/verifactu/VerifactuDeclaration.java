package es.brasatech.fastbite.application.verifactu;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The producer's declaración responsable for this version of FastBite (Real Decreto 1007/2023 and
 * Orden HAC/1177/2024): the producer states that the billing software meets the regulation. It has
 * to be readable from the software itself, so the app shows it.
 *
 * <p>Each version has its own declaration, signed during the release (scripts/sign-declaration.sh)
 * and shipped with the app. Until the running version has one, {@link #signed()} is false and the app
 * shows the text as a draft.
 */
@Component
public class VerifactuDeclaration {

    private final VerifactuSystem system;
    private final String producerAddress;
    private final String place;
    private final String signatory;
    private final DeclarationArchive archive;

    @Autowired
    public VerifactuDeclaration(VerifactuSystem system,
            @Value("${fastbite.verifactu.producer-address:}") String producerAddress,
            @Value("${fastbite.verifactu.declaration-place:}") String place,
            @Value("${fastbite.verifactu.declaration-signatory:}") String signatory,
            Optional<DeclarationArchive> archive) {
        this(system, producerAddress, place, signatory, archive.orElse(DeclarationArchive.NONE));
    }

    public VerifactuDeclaration(VerifactuSystem system, String producerAddress, String place, String signatory,
            DeclarationArchive archive) {
        this.system = system;
        this.producerAddress = producerAddress;
        this.place = place;
        this.signatory = signatory;
        this.archive = archive;
    }

    public VerifactuSystem system() { return system; }

    public String producerAddress() { return producerAddress; }

    public String place() { return place; }

    /** When this version's declaration was signed, or blank while it has none. */
    public String date() { return signedDocument().map(DeclarationArchive.SignedDeclaration::date).orElse(""); }

    /** This version's signed declaration, if it has been signed. */
    public Optional<DeclarationArchive.SignedDeclaration> signedDocument() {
        return archive.forVersion(system.version());
    }

    /** The declarations of the versions before this one, newest first: older invoices were issued under them. */
    public List<DeclarationArchive.SignedDeclaration> previous() {
        return archive.all().stream().filter(signed -> !signed.version().equals(system.version())).toList();
    }

    /** Who signs for the producer: name and position, e.g. "Ana García, administradora única". */
    public String signatory() { return signatory; }

    /** FastBite only works in VERI*FACTU mode: every record is sent to the tax agency. */
    public boolean onlyVerifactu() { return true; }

    /** One installation serves many businesses: each location is its own taxpayer and chain. */
    public boolean multipleTaxpayers() { return true; }

    /** This version's declaration is signed and every detail is filled in; otherwise the app shows a draft. */
    public boolean signed() { return signedDocument().isPresent() && missing().isEmpty(); }

    /** The details still to fill in before it can be signed. */
    public List<String> missing() {
        List<String> missing = new ArrayList<>();
        if (blank(system.producerName())) missing.add("fastbite.verifactu.producer-name");
        if (blank(system.producerNif())) missing.add("fastbite.verifactu.producer-nif");
        if (blank(producerAddress)) missing.add("fastbite.verifactu.producer-address");
        if (blank(place)) missing.add("fastbite.verifactu.declaration-place");
        if (blank(signatory)) missing.add("fastbite.verifactu.declaration-signatory");
        return missing;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
