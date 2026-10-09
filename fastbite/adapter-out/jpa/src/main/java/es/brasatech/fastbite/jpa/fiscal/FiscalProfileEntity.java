package es.brasatech.fastbite.jpa.fiscal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** The single fiscal profile row of a restaurant schema. */
@Entity(name = "FiscalProfile")
@Table(name = "fiscal_profile")
@Getter
@Setter
public class FiscalProfileEntity {

    static final int ID = 1;

    @Id
    private int id = ID;
    private String legalName;
    private String tradeName;
    private String taxId;
    private String addressLine;
    private String postalCode;
    private String city;
    private String province;
    private String phone;
    private String email;
    private String seriesPrefix;
    private BigDecimal defaultVatRate;
    private LocalDateTime updatedAt;
}
