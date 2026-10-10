package es.brasatech.fastbite.jpa.fiscal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A ticket as issued. Immutable: Hibernate never writes changes to an existing row. */
@Entity(name = "FiscalTicket")
@Table(name = "fiscal_tickets")
@Immutable
@Getter
@Setter
public class FiscalTicketEntity {

    @Id
    private String id;
    private String kind;
    private String series;
    @Column(name = "ticket_number")
    private int number;
    private String fullNumber;
    private boolean training;
    private LocalDateTime issuedLocal;
    private String issuedOffset;
    private String issuedBy;
    private String issuerLegalName;
    private String issuerTradeName;
    private String issuerTaxId;
    private String issuerAddress;
    private String issuerPostalCode;
    private String issuerCity;
    private String issuerProvince;
    private String issuerPhone;
    private String recipientTaxId;
    private String recipientCountry;
    private String recipientName;
    private String recipientAddress;
    @Column(columnDefinition = "text")
    private String lines;
    @Column(columnDefinition = "text")
    private String vatLines;
    private BigDecimal discount;
    private BigDecimal total;
    private BigDecimal vatTotal;
    private BigDecimal tip;
    private String paymentMethod;
    private BigDecimal cashReceived;
    private BigDecimal changeGiven;
    private String correctsTicketId;
    private String correctsFullNumber;
    private LocalDate correctsIssuedOn;
    private String correctionReason;
    @Column(columnDefinition = "text")
    private String orderIds;
    private String orderNumbers;
    private String tableName;
    private String paymentReference;
}
