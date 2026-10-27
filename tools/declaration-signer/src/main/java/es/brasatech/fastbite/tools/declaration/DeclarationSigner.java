package es.brasatech.fastbite.tools.declaration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The steps of signing a release's declaración responsable, run by scripts/sign-declaration.sh:
 * <pre>
 *   render --repo . --version 1.2.0 --out DIR      the app's template for 1.2.0, signed today, as DIR/declaration.html
 *   sign   --details DIR/details.properties --in DIR/declaration.pdf --out declarations/FastBite-1.2.0.pdf
 *   verify --in declarations/FastBite-1.2.0.pdf
 * </pre>
 * sign reads the certificate from BRASATECH_CERTIFICATE_PATH and its password from BRASATECH_CERTIFICATE_PASSWORD.
 */
public final class DeclarationSigner {

    private DeclarationSigner() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
        }
        Map<String, String> options = options(args);
        switch (args[0]) {
            case "render" -> {
                Path repo = Path.of(options.getOrDefault("repo", ".")).toAbsolutePath().normalize();
                Path out = Path.of(required(options, "out"));
                DeclarationDetails details = DeclarationDetails.fromRepository(repo, required(options, "version"),
                        LocalDate.now(ZoneId.of("Europe/Madrid")));
                Files.createDirectories(out);
                details.saveForSigning(out.resolve("details.properties"));
                Path page = DeclarationRenderer.render(repo, details, out);
                System.out.println("Rendered the declaration for FastBite " + details.version() + ", signed in "
                        + details.place() + " on " + details.date() + " by " + details.signatory() + ": " + page);
            }
            case "sign" -> {
                DeclarationDetails details = DeclarationDetails.load(Path.of(required(options, "details")));
                Path in = Path.of(required(options, "in"));
                Path out = Path.of(required(options, "out"));
                String certificate = System.getenv("BRASATECH_CERTIFICATE_PATH");
                String password = System.getenv("BRASATECH_CERTIFICATE_PASSWORD");
                if (certificate == null || certificate.isBlank() || password == null) {
                    throw new IllegalStateException("Set BRASATECH_CERTIFICATE_PATH and BRASATECH_CERTIFICATE_PASSWORD");
                }
                PadesSigner signer = PadesSigner.fromPkcs12(Path.of(certificate), password.toCharArray());
                Files.createDirectories(out.toAbsolutePath().getParent());
                signer.sign(in, out, "Declaración responsable del sistema informático de facturación "
                        + details.systemName() + " " + details.version(), details.place());
                List<PadesSigner.Verification> checks = PadesSigner.verify(out);
                if (checks.size() != 1 || !checks.getFirst().ok()) {
                    Files.deleteIfExists(out);
                    throw new IllegalStateException("The signed PDF did not verify: " + checks);
                }
                Path sidecar = Path.of(out.toString().replaceFirst("\\.pdf$", ".properties"));
                details.save(sidecar, signer.signerName(), Instant.now().toString(), PadesSigner.sha256(out));
                System.out.println("Signed by " + signer.signerName() + ": " + out + " (details in " + sidecar + ")");
            }
            case "verify" -> {
                Path in = Path.of(required(options, "in"));
                List<PadesSigner.Verification> checks = PadesSigner.verify(in);
                for (PadesSigner.Verification check : checks) {
                    System.out.println((check.ok() ? "VALID   " : "INVALID ") + check.signer()
                            + " | covers the whole file: " + check.coversWholeFile()
                            + " | signing-certificate-v2: " + check.hasSigningCertificateV2()
                            + " | signed at: " + (check.signedAt() == null ? "?" : check.signedAt().toInstant()));
                }
                if (checks.isEmpty() || !checks.stream().allMatch(PadesSigner.Verification::ok)) {
                    System.exit(1);
                }
            }
            default -> usage();
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                usage();
            }
            options.put(args[i].substring(2), args[i + 1]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing --" + name);
        }
        return value;
    }

    private static void usage() {
        System.err.println("usage: render --repo DIR --version x.y.z --out DIR | sign --details FILE --in PDF --out PDF | verify --in PDF");
        System.exit(2);
    }
}
