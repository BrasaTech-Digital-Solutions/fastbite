package es.brasatech.fastbite.application.digitalsign;

import es.brasatech.fastbite.domain.fiscal.SpanishTaxId;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed extractor and verifier of Spanish NIF/NIE/CIF identifiers from Digital Sign's
 * {@code signers[].certificate} JSON object.
 *
 * <p>Because the exact shape of {@code signers[].certificate} can vary by certificate authority
 * (personal FNMT, DNIe, company representative / persona jurídica), this extractor walks every
 * string value in the certificate structure and collects all valid Spanish NIF/NIE/CIF candidates
 * from plain values, {@code VATES-...} organisation identifiers, and {@code IDCES-...} serial
 * numbers.
 */
public final class CertificateNifExtractor {

    public enum Status {
        /** At least one extracted certificate NIF matches the location's normalized NIF. */
        MATCH,
        /** One or more valid Spanish NIFs were extracted, but none matches the location's NIF. */
        MISMATCH,
        /** No valid Spanish NIF could be extracted from the certificate (or expected NIF is invalid). */
        UNVERIFIED
    }

    public record VerificationResult(
            Status status,
            String matchedNif,
            Set<String> candidates) {

        public VerificationResult {
            candidates = candidates == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(candidates));
        }
    }

    private static final Pattern PREFIXED_ID = Pattern.compile(
            "(?i)(?:VATES|IDCES)[\\s\\-:]*([A-Za-z0-9][-A-Za-z0-9]{7,10})");

    private static final Pattern PLAIN_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9])(?:[Ee][Ss][\\s\\-]?)?("
                    + "[0-9]{8}-?[A-Za-z]"
                    + "|[XYZKLMxyzklm]-?[0-9]{7}-?[A-Za-z]"
                    + "|[ABCDEFGHJNPQRSUVWabcdefghjnpqrsuvw]-?[0-9]{7}-?[0-9A-Ja-j]"
                    + ")(?![A-Za-z0-9])");

    private CertificateNifExtractor() {
    }

    /**
     * Walks all strings in {@code certificateData} (maps, lists, arrays, or raw strings) and returns
     * all distinct valid Spanish NIF/NIE/CIF values in canonical uppercase form.
     */
    public static Set<String> extractCandidates(Object certificateData) {
        Set<String> found = new LinkedHashSet<>();
        walk(certificateData, found);
        return Collections.unmodifiableSet(found);
    }

    /**
     * Verifies whether {@code certificateData} contains a valid Spanish NIF matching {@code expectedNif}.
     * Fails closed: returns {@link Status#UNVERIFIED} when no NIF can be extracted.
     */
    public static VerificationResult verify(Object certificateData, String expectedNif) {
        Set<String> candidates = extractCandidates(certificateData);
        Optional<SpanishTaxId> normalizedExpected = SpanishTaxId.parse(expectedNif);
        if (normalizedExpected.isEmpty() || candidates.isEmpty()) {
            return new VerificationResult(Status.UNVERIFIED, null, candidates);
        }
        String target = normalizedExpected.get().value();
        if (candidates.contains(target)) {
            return new VerificationResult(Status.MATCH, target, candidates);
        }
        return new VerificationResult(Status.MISMATCH, null, candidates);
    }

    private static void walk(Object node, Set<String> found) {
        if (node == null) {
            return;
        }
        if (node instanceof String text) {
            extractFromString(text, found);
        } else if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                if (key != null && key.toString().toLowerCase(Locale.ROOT).contains("issuer")) {
                    continue;
                }
                walk(entry.getValue(), found);
            }
        } else if (node instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                walk(item, found);
            }
        } else if (node instanceof Object[] array) {
            for (Object item : array) {
                walk(item, found);
            }
        }
    }

    private static void extractFromString(String text, Set<String> found) {
        if (text == null || text.isBlank()) {
            return;
        }
        SpanishTaxId.parse(text).ifPresent(id -> found.add(id.value()));

        Matcher prefixed = PREFIXED_ID.matcher(text);
        while (prefixed.find()) {
            SpanishTaxId.parse(prefixed.group(1)).ifPresent(id -> found.add(id.value()));
        }

        Matcher plain = PLAIN_TOKEN.matcher(text);
        while (plain.find()) {
            SpanishTaxId.parse(plain.group(1)).ifPresent(id -> found.add(id.value()));
        }
    }
}
