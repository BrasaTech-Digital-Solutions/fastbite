package es.brasatech.fastbite.application.fiscal;

import es.brasatech.fastbite.domain.fiscal.FiscalProfile;

import java.math.BigDecimal;

/** Who issues the tickets of the restaurant the current request is for. */
public interface FiscalProfileService {

    /** The saved profile, or {@link FiscalProfile#EMPTY} if the restaurant never saved one. */
    FiscalProfile get();

    FiscalProfile save(FiscalProfile profile);

    /** The VAT rate of a product that does not set its own. */
    default BigDecimal defaultVatRate() {
        return get().defaultVatRate();
    }
}
