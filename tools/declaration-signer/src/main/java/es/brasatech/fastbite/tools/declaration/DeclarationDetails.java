package es.brasatech.fastbite.tools.declaration;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the declaration says for one version: the producer's details exactly as production uses them (the defaults in
 * application.yml; the servers don't override them), the version, and the place, date and signatory of the signature.
 */
public record DeclarationDetails(String systemName, String systemId, String version, String producerName,
        String producerNif, String producerAddress, String producerEmail, String producerWebsite, String place,
        String date, String signatory) {

    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[A-Z0-9_]+:(.*)}$");
    private static final DateTimeFormatter SPANISH_DATE =
            DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-ES"));

    public DeclarationDetails {
        require("version", version);
        for (var entry : Map.of("system-name", systemName, "system-id", systemId, "producer-name", producerName,
                "producer-nif", producerNif, "producer-address", producerAddress, "declaration-place", place,
                "declaration-signatory", signatory, "date", date).entrySet()) {
            require(entry.getKey(), entry.getValue());
        }
        if (!version.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new IllegalArgumentException("A declaration is signed for a release version x.y.z, not \"" + version + "\"");
        }
    }

    /** The details in the repository's application.yml for this version, signed today. */
    public static DeclarationDetails fromRepository(Path repo, String version, LocalDate day) throws IOException {
        Map<String, Object> yaml;
        try (InputStream in = Files.newInputStream(repo.resolve("webapplication/src/main/resources/application.yml"))) {
            yaml = new Yaml().load(in);
        }
        return new DeclarationDetails(
                setting(yaml, "fastbite.verifactu.system-name"),
                setting(yaml, "fastbite.verifactu.system-id"),
                version,
                setting(yaml, "fastbite.verifactu.producer-name"),
                setting(yaml, "fastbite.verifactu.producer-nif"),
                setting(yaml, "fastbite.verifactu.producer-address"),
                setting(yaml, "fastbite.legal.email"),
                setting(yaml, "fastbite.legal.website"),
                setting(yaml, "fastbite.verifactu.declaration-place"),
                SPANISH_DATE.format(day),
                setting(yaml, "fastbite.verifactu.declaration-signatory"));
    }

    /** The value of a dotted key, taking a "${VARIABLE:default}" placeholder's default: what production runs with. */
    @SuppressWarnings("unchecked")
    static String setting(Map<String, Object> yaml, String key) {
        Object node = yaml;
        for (String part : key.split("\\.")) {
            if (!(node instanceof Map<?, ?> map) || !map.containsKey(part)) {
                throw new IllegalStateException("application.yml has no " + key);
            }
            node = ((Map<String, Object>) map).get(part);
        }
        String value = String.valueOf(node).strip();
        Matcher placeholder = PLACEHOLDER.matcher(value);
        return placeholder.matches() ? placeholder.group(1) : value;
    }

    /** Saved next to the signed PDF, so the app can show the date and who signed without opening the PDF. */
    public void save(Path file, String signer, String signedAt, String sha256) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", version);
        properties.setProperty("date", date);
        properties.setProperty("place", place);
        properties.setProperty("signatory", signatory);
        properties.setProperty("signer", signer);
        properties.setProperty("signed-at", signedAt);
        properties.setProperty("sha256", sha256);
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            properties.store(out, "Declaración responsable de FastBite " + version + " (scripts/sign-declaration.sh)");
        }
    }

    /** The details render wrote, for the sign step. */
    public static DeclarationDetails load(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return new DeclarationDetails(p.getProperty("system-name"), p.getProperty("system-id"), p.getProperty("version"),
                p.getProperty("producer-name"), p.getProperty("producer-nif"), p.getProperty("producer-address"),
                p.getProperty("producer-email"), p.getProperty("producer-website"), p.getProperty("place"),
                p.getProperty("date"), p.getProperty("signatory"));
    }

    /** Everything, for the sign step to pick up after Chrome has printed the page. */
    public void saveForSigning(Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("system-name", systemName);
        p.setProperty("system-id", systemId);
        p.setProperty("version", version);
        p.setProperty("producer-name", producerName);
        p.setProperty("producer-nif", producerNif);
        p.setProperty("producer-address", producerAddress);
        p.setProperty("producer-email", producerEmail);
        p.setProperty("producer-website", producerWebsite);
        p.setProperty("place", place);
        p.setProperty("date", date);
        p.setProperty("signatory", signatory);
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(out, null);
        }
    }

    private static void require(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("The declaration needs " + name);
        }
    }
}
