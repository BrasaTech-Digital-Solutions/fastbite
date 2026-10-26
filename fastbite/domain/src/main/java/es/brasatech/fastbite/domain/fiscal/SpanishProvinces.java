package es.brasatech.fastbite.domain.fiscal;

import java.util.List;
import java.util.Optional;

/**
 * Spanish provinces by the two digits every postal code in them starts with.
 */
public final class SpanishProvinces {

    /** Index i holds the province of postal codes starting with i + 1 (01 is Álava, 52 Melilla). */
    public static final List<String> NAMES = List.of(
            "Álava", "Albacete", "Alicante", "Almería", "Ávila", "Badajoz", "Illes Balears", "Barcelona",
            "Burgos", "Cáceres", "Cádiz", "Castellón", "Ciudad Real", "Córdoba", "A Coruña", "Cuenca",
            "Girona", "Granada", "Guadalajara", "Gipuzkoa", "Huelva", "Huesca", "Jaén", "León", "Lleida",
            "La Rioja", "Lugo", "Madrid", "Málaga", "Murcia", "Navarra", "Ourense", "Asturias", "Palencia",
            "Las Palmas", "Pontevedra", "Salamanca", "Santa Cruz de Tenerife", "Cantabria", "Segovia",
            "Sevilla", "Soria", "Tarragona", "Teruel", "Toledo", "Valencia", "Valladolid", "Bizkaia",
            "Zamora", "Zaragoza", "Ceuta", "Melilla");

    private SpanishProvinces() {
    }

    public static boolean isValidPostalCode(String postalCode) {
        return ofPostalCode(postalCode).isPresent();
    }

    /** The province a postal code belongs to, or empty if it is not a Spanish postal code. */
    public static Optional<String> ofPostalCode(String postalCode) {
        if (postalCode == null || !postalCode.matches("\\d{5}")) {
            return Optional.empty();
        }
        int prefix = Integer.parseInt(postalCode.substring(0, 2));
        return prefix >= 1 && prefix <= NAMES.size() ? Optional.of(NAMES.get(prefix - 1)) : Optional.empty();
    }

    /**
     * Places where Spanish VAT does not apply: the Canary Islands (IGIC), Ceuta and Melilla (IPSI).
     * FastBite only issues tickets with Spanish VAT.
     */
    public static boolean outsideVatTerritory(String postalCode) {
        return postalCode != null && (postalCode.startsWith("35") || postalCode.startsWith("38")
                || postalCode.startsWith("51") || postalCode.startsWith("52"));
    }

    /**
     * Foral territories (Basque Country and Navarra) that use their own billing systems
     * (TicketBAI in the Basque Country; Navarra's own rules) outside AEAT VERI*FACTU.
     */
    public static boolean isForalTerritory(String postalCode) {
        return postalCode != null && (postalCode.startsWith("01") || postalCode.startsWith("20")
                || postalCode.startsWith("48") || postalCode.startsWith("31"));
    }
}
