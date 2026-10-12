package es.brasatech.fastbite.jpa.verifactu;

import es.brasatech.fastbite.application.verifactu.VerifactuRepository;
import es.brasatech.fastbite.domain.verifactu.VerifactuRecord;
import es.brasatech.fastbite.domain.verifactu.VerifactuStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Billing records of the current restaurant schema. */
@Repository
@Profile("jpa")
@Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
public class VerifactuRepositoryJpa implements VerifactuRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<VerifactuRecord> lockChain(String chain) {
        // Touching the row locks it until the transaction ends; the first record creates it
        int touched = entityManager.createNativeQuery("UPDATE verifactu_chain SET records = records WHERE chain = :chain")
                .setParameter("chain", chain).executeUpdate();
        if (touched == 0) {
            entityManager.createNativeQuery("INSERT INTO verifactu_chain (chain, last_record_id, records) VALUES (:chain, NULL, 0)")
                    .setParameter("chain", chain).executeUpdate();
            return Optional.empty();
        }
        Object last = entityManager.createNativeQuery("SELECT last_record_id FROM verifactu_chain WHERE chain = :chain")
                .setParameter("chain", chain).getSingleResult();
        return last == null ? Optional.empty()
                : Optional.ofNullable(entityManager.find(VerifactuRecordEntity.class, last.toString())).map(this::toRecord);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public VerifactuRecord append(VerifactuRecord record) {
        entityManager.persist(toEntity(record));
        int moved = entityManager.createNativeQuery("UPDATE verifactu_chain SET last_record_id = :record, records = records + 1 "
                        + "WHERE chain = :chain AND records = :before")
                .setParameter("record", record.id()).setParameter("chain", record.chain())
                .setParameter("before", record.sequence() - 1).executeUpdate();
        if (moved != 1) {
            throw new IllegalStateException("Billing record " + record.sequence() + " does not follow the end of chain " + record.chain());
        }
        entityManager.flush();
        return record;
    }

    @Override
    public Optional<VerifactuRecord> findById(String recordId) {
        return Optional.ofNullable(entityManager.find(VerifactuRecordEntity.class, recordId)).map(this::toRecord);
    }

    @Override
    public Optional<VerifactuRecord> findByTicketId(String ticketId) {
        return entityManager.createQuery("select r from VerifactuRecord r where r.invoiceTicketId = :ticket "
                        + "order by r.sequence desc", VerifactuRecordEntity.class)
                .setParameter("ticket", ticketId).setMaxResults(1).getResultStream().findFirst().map(this::toRecord);
    }

    @Override
    public Optional<VerifactuRecord> findLast(String chain) {
        return entityManager.createQuery("select r from VerifactuRecord r where r.chain = :chain order by r.sequence desc",
                        VerifactuRecordEntity.class)
                .setParameter("chain", chain).setMaxResults(1).getResultStream().findFirst().map(this::toRecord);
    }

    @Override
    public List<VerifactuRecord> findPending(String chain, int limit) {
        return byStatus(chain, VerifactuStatus.PENDING, "asc", limit);
    }

    @Override
    public List<VerifactuRecord> findToAmend(String chain, int limit) {
        return entityManager.createQuery("select r from VerifactuRecord r where r.chain = :chain and r.status in :statuses "
                        + "order by r.sequence desc", VerifactuRecordEntity.class)
                .setParameter("chain", chain)
                .setParameter("statuses", List.of(VerifactuStatus.REJECTED.name(), VerifactuStatus.ACCEPTED_WITH_ERRORS.name()))
                .setMaxResults(limit).getResultStream().map(this::toRecord).toList();
    }

    private List<VerifactuRecord> byStatus(String chain, VerifactuStatus status, String order, int limit) {
        return entityManager.createQuery("select r from VerifactuRecord r where r.chain = :chain and r.status = :status "
                        + "order by r.sequence " + order, VerifactuRecordEntity.class)
                .setParameter("chain", chain).setParameter("status", status.name()).setMaxResults(limit)
                .getResultStream().map(this::toRecord).toList();
    }

    @Override
    public List<VerifactuRecord> findAll(String chain) {
        return entityManager.createQuery("select r from VerifactuRecord r where r.chain = :chain order by r.sequence",
                        VerifactuRecordEntity.class)
                .setParameter("chain", chain).getResultStream().map(this::toRecord).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void updateSubmission(String recordId, VerifactuRecord.Submission submission) {
        VerifactuRecordEntity entity = entityManager.find(VerifactuRecordEntity.class, recordId);
        if (entity == null) {
            throw new IllegalArgumentException("Billing record " + recordId + " not found");
        }
        entity.setStatus(submission.status().name());
        entity.setAttempts(submission.attempts());
        entity.setLastError(submission.error() == null ? null
                : submission.error().substring(0, Math.min(500, submission.error().length())));
        entity.setCsv(submission.csv());
        entity.setSubmittedAt(submission.at() == null ? null : submission.at().toLocalDateTime());
    }

    @Override
    public Map<VerifactuStatus, Long> countByStatus(String chain) {
        Map<VerifactuStatus, Long> counts = new EnumMap<>(VerifactuStatus.class);
        entityManager.createQuery("select r.status, count(r) from VerifactuRecord r where r.chain = :chain group by r.status",
                        Object[].class)
                .setParameter("chain", chain).getResultList()
                .forEach(row -> counts.put(VerifactuStatus.valueOf(row[0].toString()), ((Number) row[1]).longValue()));
        return counts;
    }

    private VerifactuRecordEntity toEntity(VerifactuRecord record) {
        var entity = new VerifactuRecordEntity();
        entity.setId(record.id());
        entity.setTicketId(record.amendment() ? null : record.ticketId());
        entity.setInvoiceTicketId(record.ticketId());
        entity.setChain(record.chain());
        entity.setSequence(record.sequence());
        entity.setInvoiceType(record.invoiceType());
        entity.setIssuerNif(record.issuerNif());
        entity.setIssuerName(record.issuerName());
        entity.setInvoiceNumber(record.invoiceNumber());
        entity.setIssueDate(record.issueDate());
        entity.setGeneratedLocal(record.generatedAt().toLocalDateTime());
        entity.setGeneratedOffset(record.generatedAt().getOffset().getId());
        entity.setTaxTotal(record.taxTotal());
        entity.setAmountTotal(record.amountTotal());
        if (record.previous() != null) {
            entity.setPreviousIssuerNif(record.previous().issuerNif());
            entity.setPreviousInvoiceNumber(record.previous().invoiceNumber());
            entity.setPreviousIssueDate(record.previous().issueDate());
            entity.setPreviousFingerprint(record.previous().fingerprint());
        }
        entity.setFingerprint(record.fingerprint());
        entity.setQrUrl(record.qrUrl());
        if (record.corrected() != null) {
            entity.setCorrectedIssuerNif(record.corrected().issuerNif());
            entity.setCorrectedInvoiceNumber(record.corrected().invoiceNumber());
            entity.setCorrectedIssueDate(record.corrected().issueDate());
        }
        if (record.amends() != null) {
            entity.setAmendsRecordId(record.amends().recordId());
            entity.setAmendsAfterRejection(record.amends().afterRejection());
        }
        entity.setTraining(record.training());
        entity.setStatus(record.submission().status().name());
        entity.setAttempts(record.submission().attempts());
        entity.setLastError(record.submission().error());
        entity.setCsv(record.submission().csv());
        return entity;
    }

    private VerifactuRecord toRecord(VerifactuRecordEntity entity) {
        ZoneOffset offset = ZoneOffset.of(entity.getGeneratedOffset());
        var previous = entity.getPreviousFingerprint() == null ? null : new VerifactuRecord.Link(
                entity.getPreviousIssuerNif(), entity.getPreviousInvoiceNumber(), entity.getPreviousIssueDate(),
                entity.getPreviousFingerprint());
        var corrected = entity.getCorrectedInvoiceNumber() == null ? null : new VerifactuRecord.Link(
                entity.getCorrectedIssuerNif(), entity.getCorrectedInvoiceNumber(), entity.getCorrectedIssueDate(), null);
        var amends = entity.getAmendsRecordId() == null ? null : new VerifactuRecord.Amendment(
                entity.getAmendsRecordId(), Boolean.TRUE.equals(entity.getAmendsAfterRejection()));
        var submission = new VerifactuRecord.Submission(VerifactuStatus.valueOf(entity.getStatus()), entity.getAttempts(),
                entity.getLastError(), entity.getCsv(),
                entity.getSubmittedAt() == null ? null : OffsetDateTime.of(entity.getSubmittedAt(), offset));
        return new VerifactuRecord(entity.getId(), entity.getInvoiceTicketId(), entity.getChain(), entity.getSequence(),
                entity.getInvoiceType(), entity.getIssuerNif(), entity.getIssuerName(), entity.getInvoiceNumber(),
                entity.getIssueDate(), OffsetDateTime.of(entity.getGeneratedLocal(), offset), entity.getTaxTotal(),
                entity.getAmountTotal(), previous, entity.getFingerprint(), entity.getQrUrl(), corrected,
                amends, entity.isTraining(), submission);
    }
}
