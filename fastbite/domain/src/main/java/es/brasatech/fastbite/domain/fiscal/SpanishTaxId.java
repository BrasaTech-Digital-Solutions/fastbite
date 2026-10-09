package es.brasatech.fastbite.domain.fiscal;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A Spanish tax identification number (NIF): the DNI of a Spanish citizen, the NIE of a foreign
 * resident, the NIF of a company (formerly CIF) or one of the special personal NIFs (K, L, M).
 * Only numbers whose control character is right are accepted.
 */
public record SpanishTaxId(String value, Kind kind) {

    public enum Kind {
        /** A Spanish citizen: 8 digits and a letter. Self-employed people use it as their tax number. */
        DNI,
        /** A foreign resident: X, Y or Z, 7 digits and a letter. */
        NIE,
        /** A company or other legal entity: a letter, 7 digits and a control digit or letter. */
        COMPANY,
        /** A person without DNI or NIE (K, L or M, 7 digits and a letter). */
        OTHER_PERSON;

        /** People, as opposed to companies. */
        public boolean naturalPerson() {
            return this != COMPANY;
        }
    }

    private static final String PERSON_LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE";
    private static final String COMPANY_LETTERS = "JABCDEFGHI";
    private static final Pattern DNI = Pattern.compile("\\d{8}[A-Z]");
    private static final Pattern NIE = Pattern.compile("[XYZ]\\d{7}[A-Z]");
    private static final Pattern OTHER_PERSON = Pattern.compile("[KLM]\\d{7}[A-Z]");
    private static final Pattern COMPANY = Pattern.compile("[ABCDEFGHJNPQRSUVW]\\d{7}[0-9A-J]");

    /**
     * Reads a tax number as people type it: in any case, with spaces, dots or dashes, with or
     * without the "ES" country prefix.
     *
     * @return the number in its standard form, or empty if it is not a valid Spanish NIF
     */
    public static Optional<SpanishTaxId> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String value = input.toUpperCase(Locale.ROOT).replaceAll("[\\s.\\-/]", "");
        if (value.startsWith("ES") && value.length() == 11) {
            value = value.substring(2);
        }
        if (DNI.matcher(value).matches()) {
            return personLetterMatches(value.substring(0, 8), value.charAt(8))
                    ? Optional.of(new SpanishTaxId(value, Kind.DNI)) : Optional.empty();
        }
        if (NIE.matcher(value).matches()) {
            String digits = "XYZ".indexOf(value.charAt(0)) + value.substring(1, 8);
            return personLetterMatches(digits, value.charAt(8))
                    ? Optional.of(new SpanishTaxId(value, Kind.NIE)) : Optional.empty();
        }
        if (OTHER_PERSON.matcher(value).matches()) {
            return personLetterMatches(value.substring(1, 8), value.charAt(8))
                    ? Optional.of(new SpanishTaxId(value, Kind.OTHER_PERSON)) : Optional.empty();
        }
        if (COMPANY.matcher(value).matches()) {
            return companyControlMatches(value)
                    ? Optional.of(new SpanishTaxId(value, Kind.COMPANY)) : Optional.empty();
        }
        return Optional.empty();
    }

    public static boolean isValid(String input) {
        return parse(input).isPresent();
    }

    private static boolean personLetterMatches(String digits, char letter) {
        return PERSON_LETTERS.charAt((int) (Long.parseLong(digits) % 23)) == letter;
    }

    private static boolean companyControlMatches(String value) {
        String digits = value.substring(1, 8);
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(i) - '0';
            if (i % 2 == 0) {
                int doubled = digit * 2;
                sum += doubled / 10 + doubled % 10;
            } else {
                sum += digit;
            }
        }
        int control = (10 - sum % 10) % 10;
        char type = value.charAt(0);
        char given = value.charAt(8);
        boolean letterGiven = Character.isLetter(given);
        // Entities without share capital always end in a letter, companies with share capital in a digit
        if ("NPQRSW".indexOf(type) >= 0 && !letterGiven) {
            return false;
        }
        if ("ABEH".indexOf(type) >= 0 && letterGiven) {
            return false;
        }
        return letterGiven ? COMPANY_LETTERS.charAt(control) == given : control == given - '0';
    }
}
