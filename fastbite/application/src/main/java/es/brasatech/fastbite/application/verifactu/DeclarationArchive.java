package es.brasatech.fastbite.application.verifactu;

import java.util.List;
import java.util.Optional;

/**
 * The declaraciones responsables the producer has signed, one per version (Orden HAC/1177/2024, art. 15). They are
 * signed on the producer's machine during a release (scripts/sign-declaration.sh) and shipped with the app.
 */
public interface DeclarationArchive {

    DeclarationArchive NONE = new DeclarationArchive() {
        @Override public Optional<SignedDeclaration> forVersion(String version) { return Optional.empty(); }
        @Override public List<SignedDeclaration> all() { return List.of(); }
    };

    /** One signed declaration: the version it declares, where and when it was signed, by whom. */
    record SignedDeclaration(String version, String date, String place, String signatory, String signer,
            String signedAt, String sha256) {

        /** The file name of the signed PDF. */
        public String fileName() { return "FastBite-" + version + ".pdf"; }
    }

    Optional<SignedDeclaration> forVersion(String version);

    /** Every signed declaration, the newest version first. */
    List<SignedDeclaration> all();
}
