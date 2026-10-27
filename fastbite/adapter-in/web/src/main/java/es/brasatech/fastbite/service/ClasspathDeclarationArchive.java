package es.brasatech.fastbite.service;

import es.brasatech.fastbite.application.verifactu.DeclarationArchive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * The signed declarations shipped in the image: declarations/FastBite-x.y.z.pdf, each with the .properties that
 * scripts/sign-declaration.sh wrote next to it, listed in declarations/declarations.list (a list, because a native
 * image can't search its resources). Read once at startup; a PDF whose hash doesn't match its details is ignored.
 */
@Component
@Slf4j
public class ClasspathDeclarationArchive implements DeclarationArchive {

    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final Comparator<SignedDeclaration> NEWEST_FIRST = Comparator.comparing(
            (SignedDeclaration signed) -> versionKey(signed.version())).reversed();

    private final List<SignedDeclaration> declarations;

    public ClasspathDeclarationArchive() {
        this.declarations = load();
    }

    @Override
    public Optional<SignedDeclaration> forVersion(String version) {
        return declarations.stream().filter(signed -> signed.version().equals(version)).findFirst();
    }

    @Override
    public List<SignedDeclaration> all() {
        return declarations;
    }

    /** The signed PDF of a version, if there is one. */
    public Optional<Resource> pdf(String version) {
        return forVersion(version).map(signed -> new ClassPathResource("declarations/" + signed.fileName()));
    }

    private static List<SignedDeclaration> load() {
        Resource list = new ClassPathResource("declarations/declarations.list");
        if (!list.exists()) {
            return List.of();
        }
        List<SignedDeclaration> found = new ArrayList<>();
        try (InputStream in = list.getInputStream()) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String version = line.strip();
                if (VERSION.matcher(version).matches()) {
                    read(version).ifPresent(found::add);
                }
            }
        } catch (IOException e) {
            log.error("Could not read the signed declarations: {}", e.getMessage());
        }
        found.sort(NEWEST_FIRST);
        return List.copyOf(found);
    }

    private static Optional<SignedDeclaration> read(String version) throws IOException {
        Resource details = new ClassPathResource("declarations/FastBite-" + version + ".properties");
        Resource pdf = new ClassPathResource("declarations/FastBite-" + version + ".pdf");
        if (!details.exists() || !pdf.exists()) {
            log.error("The signed declaration of FastBite {} is incomplete: its PDF or its details are missing", version);
            return Optional.empty();
        }
        Properties p = new Properties();
        try (Reader in = new InputStreamReader(details.getInputStream(), StandardCharsets.UTF_8)) {
            p.load(in);
        }
        String sha256;
        try (InputStream in = pdf.getInputStream()) {
            sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        if (!version.equals(p.getProperty("version")) || !sha256.equals(p.getProperty("sha256"))) {
            log.error("The signed declaration of FastBite {} doesn't match its details: ignored", version);
            return Optional.empty();
        }
        return Optional.of(new SignedDeclaration(version, p.getProperty("date"), p.getProperty("place"),
                p.getProperty("signatory"), p.getProperty("signer"), p.getProperty("signed-at"), sha256));
    }

    private static String versionKey(String version) {
        StringBuilder key = new StringBuilder();
        for (String part : version.split("\\.")) {
            key.append(String.format("%06d", Integer.parseInt(part)));
        }
        return key.toString();
    }
}
