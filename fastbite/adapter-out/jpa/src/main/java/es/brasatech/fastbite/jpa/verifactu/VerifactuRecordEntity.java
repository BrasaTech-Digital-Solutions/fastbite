package es.brasatech.fastbite.jpa.verifactu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A billing record row; only the submission columns are ever updated. */
@Entity(name = "VerifactuRecord")
@Table(name = "verifactu_records")
@Getter
@Setter
public class VerifactuRecordEntity {

    @Id
    private String id;
    @Column(updatable = false)
    private String ticketId;
    @Column(updatable = false)
    private String chain;
    @Column(name = "sequence_number", updatable = false)
    private int sequence;
    @Column(updatable = false)
    private String invoiceType;
    @Column(updatable = false)
    private String issuerNif;
    @Column(updatable = false)
    private String issuerName;
    @Column(updatable = false)
    private String invoiceNumber;
    @Column(updatable = false)
    private LocalDate issueDate;
    @Column(updatable = false)
    private LocalDateTime generatedLocal;
    @Column(updatable = false)
    private String generatedOffset;
    @Column(updatable = false)
    private BigDecimal taxTotal;
    @Column(updatable = false)
    private BigDecimal amountTotal;
    @Column(updatable = false)
    private String previousIssuerNif;
    @Column(updatable = false)
    private String previousInvoiceNumber;
    @Column(updatable = false)
    private LocalDate previousIssueDate;
    @Column(updatable = false)
    private String previousFingerprint;
    @Column(updatable = false)
    private String fingerprint;
    @Column(updatable = false)
    private String qrUrl;
    @Column(updatable = false)
    private String correctedIssuerNif;
    @Column(updatable = false)
    private String correctedInvoiceNumber;
    @Column(updatable = false)
    private LocalDate correctedIssueDate;
    @Column(updatable = false)
    private boolean training;
    private String status;
    private int attempts;
    private String lastError;
    private String csv;
    private LocalDateTime submittedAt;
}
