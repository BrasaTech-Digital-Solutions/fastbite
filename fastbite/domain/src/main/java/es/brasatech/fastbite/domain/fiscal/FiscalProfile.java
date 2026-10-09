package es.brasatech.fastbite.domain.fiscal;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Who issues the tickets of one location, and where. Every location keeps its own profile: two
 * locations of the same owner can belong to different businesses.
 *
 * @param legalName     the issuer's legal name: the company name, or the full name of a self-employed person
 * @param tradeName     the name guests know, printed large on the ticket; optional
 * @param taxId         the issuer's NIF, in standard form
 * @param seriesPrefix  starts every ticket number of this location, so its numbering never mixes with another's
 * @param defaultVatRate the VAT rate of products that do not set their own
 */
public record FiscalProfile(String legalName, String tradeName, String taxId, String addressLine,
        String postalCode, String city, String province, String phone, String email, String seriesPrefix,
        BigDecimal defaultVatRate) {

    public static final FiscalProfile EMPTY = new FiscalProfile(null, null, null, null, null, null, null, null,
            null, null, VatRates.DEFAULT);

    private static final int MAX_PREFIX = 8;

    public FiscalProfile {
        legalName = clean(legalName);
        tradeName = clean(tradeName);
        taxId = SpanishTaxId.parse(taxId).map(SpanishTaxId::value).orElse(clean(taxId));
        addressLine = clean(addressLine);
        postalCode = clean(postalCode);
        city = clean(city);
        province = clean(province);
        phone = clean(phone);
        email = clean(email);
        seriesPrefix = seriesPrefix == null ? null : clean(seriesPrefix.toUpperCase(Locale.ROOT));
        defaultVatRate = VatRates.isAllowed(defaultVatRate) ? defaultVatRate.setScale(2) : VatRates.DEFAULT;
    }

    /** The name to show guests. */
    public String displayName() {
        return tradeName != null ? tradeName : legalName;
    }

    public SpanishTaxId.Kind taxIdKind() {
        return SpanishTaxId.parse(taxId).map(SpanishTaxId::kind).orElse(null);
    }

    /** True when the location may issue tickets: every required field is present and valid. */
    public boolean complete() {
        return problems().isEmpty();
    }

    /** The fields still to fill in, for the opening checklist. */
    public List<String> missingFields() {
        List<String> missing = new ArrayList<>();
        if (legalName == null) missing.add("legalName");
        if (taxId == null) missing.add("taxId");
        if (addressLine == null) missing.add("addressLine");
        if (postalCode == null) missing.add("postalCode");
        if (city == null) missing.add("city");
        if (province == null) missing.add("province");
        if (seriesPrefix == null) missing.add("seriesPrefix");
        return missing;
    }

    /**
     * Each field that is missing or wrong, with the key of the message that explains it. Empty
     * when the profile can be used to issue tickets.
     */
    public Map<String, String> problems() {
        Map<String, String> problems = new LinkedHashMap<>();
        missingFields().forEach(field -> problems.put(field, "fiscal.error.required"));
        if (legalName != null && legalName.length() > 120) problems.put("legalName", "fiscal.error.legalName.length");
        if (tradeName != null && tradeName.length() > 80) problems.put("tradeName", "fiscal.error.tradeName.length");
        if (taxId != null && !SpanishTaxId.isValid(taxId)) problems.put("taxId", "fiscal.error.taxId");
        if (addressLine != null && addressLine.length() > 160) problems.put("addressLine", "fiscal.error.addressLine.length");
        if (postalCode != null && !SpanishProvinces.isValidPostalCode(postalCode)) {
            problems.put("postalCode", "fiscal.error.postalCode");
        } else if (SpanishProvinces.outsideVatTerritory(postalCode)) {
            problems.put("postalCode", "fiscal.error.postalCode.territory");
        }
        if (city != null && city.length() > 80) problems.put("city", "fiscal.error.city.length");
        if (phone != null && !phone.matches("[+0-9 ()-]{6,20}")) problems.put("phone", "fiscal.error.phone");
        if (email != null && !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) problems.put("email", "fiscal.error.email");
        if (seriesPrefix != null && !seriesPrefix.matches("[A-Z0-9]{1," + MAX_PREFIX + "}")) {
            problems.put("seriesPrefix", "fiscal.error.seriesPrefix");
        }
        return problems;
    }

    /** A short ticket series prefix made from a business name: "Kebab House Centro" becomes "KHC". */
    public static String suggestPrefix(String name) {
        if (name == null || name.isBlank()) {
            return "T";
        }
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " ").trim();
        String[] words = Arrays.stream(ascii.split("\\s+")).filter(word -> !word.isEmpty()).toArray(String[]::new);
        if (words.length == 0) {
            return "T";
        }
        String prefix = words.length == 1
                ? words[0].substring(0, Math.min(3, words[0].length()))
                : Arrays.stream(words).limit(4).map(word -> word.substring(0, 1)).reduce("", String::concat);
        return prefix.length() > MAX_PREFIX ? prefix.substring(0, MAX_PREFIX) : prefix;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip().replaceAll("\\s+", " ");
        return trimmed.isEmpty() ? null : trimmed;
    }
}
