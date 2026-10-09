package es.brasatech.fastbite.jpa.allergen;

import es.brasatech.fastbite.domain.allergen.Allergen;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Set;

/** Stores allergens as codes separated by commas, keeping "not declared" (null) apart from "none" (''). */
@Converter
public class AllergensConverter implements AttributeConverter<Set<Allergen>, String> {

    @Override
    public String convertToDatabaseColumn(Set<Allergen> allergens) {
        return Allergen.format(allergens);
    }

    @Override
    public Set<Allergen> convertToEntityAttribute(String stored) {
        return Allergen.parse(stored);
    }
}
