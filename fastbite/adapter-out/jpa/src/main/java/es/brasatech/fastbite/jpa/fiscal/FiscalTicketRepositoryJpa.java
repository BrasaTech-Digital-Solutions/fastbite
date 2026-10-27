package es.brasatech.fastbite.jpa.fiscal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.brasatech.fastbite.application.fiscal.FiscalTicketRepository;
import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.PaymentMethod;
import es.brasatech.fastbite.domain.fiscal.TicketCancellation;
import es.brasatech.fastbite.domain.fiscal.TicketKind;
import es.brasatech.fastbite.domain.fiscal.TicketLine;
import es.brasatech.fastbite.domain.fiscal.TicketRecipient;
import es.brasatech.fastbite.domain.fiscal.VatBreakdown;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Tickets of the current restaurant schema; rows are inserted and read, never updated or deleted. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class FiscalTicketRepositoryJpa implements FiscalTicketRepository {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<TicketLine>> LINES = new TypeReference<>() {
    };
    private static final TypeReference<List<VatBreakdown.Line>> VAT_LINES = new TypeReference<>() {
    };

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public int nextNumber(String series) {
        // The update locks the series row until the transaction ends: concurrent tickets wait their turn
        if (increment(series) == 0) {
            createSeries(series);
            increment(series);
        }
        return ((Number) entityManager.createNativeQuery("SELECT last_number FROM fiscal_series WHERE series = :series")
                .setParameter("series", series).getSingleResult()).intValue();
    }

    private int increment(String series) {
        return entityManager.createNativeQuery("UPDATE fiscal_series SET last_number = last_number + 1 WHERE series = :series")
                .setParameter("series", series).executeUpdate();
    }

    /** The first ticket of a series creates it; an existing row is never written here. */
    private void createSeries(String series) {
        String database = entityManager.unwrap(Session.class).doReturningWork(connection ->
                connection.getMetaData().getDatabaseProductName());
        String insert = "PostgreSQL".equals(database)
                ? "INSERT INTO fiscal_series (series, last_number) VALUES (:series, 0) ON CONFLICT (series) DO NOTHING"
                : "INSERT INTO fiscal_series (series, last_number) SELECT :series, 0 "
                        + "WHERE NOT EXISTS (SELECT 1 FROM fiscal_series WHERE series = :series)";
        entityManager.createNativeQuery(insert).setParameter("series", series).executeUpdate();
    }

    @Override
    public FiscalTicket add(FiscalTicket ticket) {
        if (entityManager.find(FiscalTicketEntity.class, ticket.id()) != null) {
            throw new IllegalStateException("Ticket " + ticket.fullNumber() + " was already issued");
        }
        entityManager.persist(toEntity(ticket));
        if (ticket.kind() == TicketKind.SIMPLIFIED || ticket.kind() == TicketKind.FULL) {
            // The primary key refuses a second ticket for the same order
            for (String orderId : ticket.orderIds()) {
                entityManager.createNativeQuery("INSERT INTO fiscal_sale_orders (order_id, ticket_id) VALUES (:order, :ticket)")
                        .setParameter("order", orderId).setParameter("ticket", ticket.id()).executeUpdate();
            }
        }
        entityManager.flush();
        return ticket;
    }

    @Override
    public void cancel(TicketCancellation cancellation) {
        entityManager.createNativeQuery("INSERT INTO fiscal_ticket_cancellations "
                        + "(ticket_id, cancelled_local, cancelled_offset, cancelled_by, reason) "
                        + "VALUES (:ticket, :local, :offset, :by, :reason)")
                .setParameter("ticket", cancellation.ticketId())
                .setParameter("local", cancellation.at().toLocalDateTime())
                .setParameter("offset", cancellation.at().getOffset().getId())
                .setParameter("by", cancellation.by())
                .setParameter("reason", cancellation.reason())
                .executeUpdate();
        // There was no sale: the orders can be charged again
        entityManager.createNativeQuery("DELETE FROM fiscal_sale_orders WHERE ticket_id = :ticket")
                .setParameter("ticket", cancellation.ticketId()).executeUpdate();
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<TicketCancellation> findCancellation(String ticketId) {
        List<?> rows = entityManager.createNativeQuery("SELECT cancelled_local, cancelled_offset, cancelled_by, reason "
                        + "FROM fiscal_ticket_cancellations WHERE ticket_id = :ticket")
                .setParameter("ticket", ticketId).getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.getFirst();
        return Optional.of(toCancellation(ticketId, row[0], row[1], row[2], row[3]));
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<TicketCancellation> findCancellationsBetween(OffsetDateTime from, OffsetDateTime to) {
        List<?> rows = entityManager.createNativeQuery("SELECT ticket_id, cancelled_local, cancelled_offset, cancelled_by, reason "
                        + "FROM fiscal_ticket_cancellations WHERE cancelled_local >= :from AND cancelled_local < :to "
                        + "ORDER BY cancelled_local")
                .setParameter("from", from.toLocalDateTime()).setParameter("to", to.toLocalDateTime()).getResultList();
        return rows.stream().map(found -> (Object[]) found)
                .map(row -> toCancellation(row[0].toString(), row[1], row[2], row[3], row[4])).toList();
    }

    private static TicketCancellation toCancellation(String ticketId, Object at, Object offset, Object by, Object reason) {
        java.time.LocalDateTime local = at instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime()
                : (java.time.LocalDateTime) at;
        return new TicketCancellation(ticketId, OffsetDateTime.of(local, java.time.ZoneOffset.of(offset.toString())),
                by == null ? null : by.toString(), reason.toString());
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<FiscalTicket> findById(String id) {
        return Optional.ofNullable(entityManager.find(FiscalTicketEntity.class, id)).map(this::toTicket);
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<FiscalTicket> findSaleByOrderId(String orderId) {
        List<?> ids = entityManager.createNativeQuery("SELECT ticket_id FROM fiscal_sale_orders WHERE order_id = :order")
                .setParameter("order", orderId).getResultList();
        return ids.isEmpty() ? Optional.empty() : findById(ids.getFirst().toString());
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Optional<FiscalTicket> findCorrection(String ticketId, TicketKind kind) {
        return entityManager.createQuery("select t from FiscalTicket t where t.correctsTicketId = :ticket and t.kind = :kind",
                        FiscalTicketEntity.class)
                .setParameter("ticket", ticketId).setParameter("kind", kind.name())
                .getResultStream().findFirst().map(this::toTicket);
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<FiscalTicket> findIssuedBetween(OffsetDateTime from, OffsetDateTime to) {
        return entityManager.createQuery("select t from FiscalTicket t where t.issuedLocal >= :from and t.issuedLocal < :to "
                        + "order by t.issuedLocal, t.series, t.number", FiscalTicketEntity.class)
                .setParameter("from", from.toLocalDateTime()).setParameter("to", to.toLocalDateTime())
                .getResultStream().map(this::toTicket).toList();
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<FiscalTicket> findAll() {
        return entityManager.createQuery("select t from FiscalTicket t order by t.issuedLocal, t.series, t.number",
                        FiscalTicketEntity.class)
                .getResultStream().map(this::toTicket).toList();
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean anyRealTicket() {
        return !entityManager.createQuery("select t.id from FiscalTicket t where t.training = false", String.class)
                .setMaxResults(1).getResultList().isEmpty();
    }

    private FiscalTicketEntity toEntity(FiscalTicket ticket) {
        var entity = new FiscalTicketEntity();
        entity.setId(ticket.id());
        entity.setKind(ticket.kind().name());
        entity.setSeries(ticket.series());
        entity.setNumber(ticket.number());
        entity.setFullNumber(ticket.fullNumber());
        entity.setTraining(ticket.training());
        entity.setIssuedLocal(ticket.issuedAt().toLocalDateTime());
        entity.setIssuedOffset(ticket.issuedAt().getOffset().getId());
        entity.setIssuedBy(ticket.issuedBy());
        var issuer = ticket.issuer();
        entity.setIssuerLegalName(issuer.legalName());
        entity.setIssuerTradeName(issuer.tradeName());
        entity.setIssuerTaxId(issuer.taxId());
        entity.setIssuerAddress(issuer.addressLine());
        entity.setIssuerPostalCode(issuer.postalCode());
        entity.setIssuerCity(issuer.city());
        entity.setIssuerProvince(issuer.province());
        entity.setIssuerPhone(issuer.phone());
        if (ticket.recipient() != null) {
            entity.setRecipientTaxId(ticket.recipient().taxId());
            entity.setRecipientCountry(ticket.recipient().country());
            entity.setRecipientName(ticket.recipient().name());
            entity.setRecipientAddress(ticket.recipient().address());
        }
        entity.setLines(write(ticket.lines()));
        entity.setVatLines(write(ticket.vat().lines()));
        entity.setDiscount(ticket.discount());
        entity.setTotal(ticket.total());
        entity.setVatTotal(ticket.vat().vat());
        entity.setTip(ticket.tip());
        entity.setPaymentMethod(ticket.paymentMethod().name());
        entity.setCashReceived(ticket.cashReceived());
        entity.setChangeGiven(ticket.change());
        if (ticket.corrects() != null) {
            entity.setCorrectsTicketId(ticket.corrects().ticketId());
            entity.setCorrectsFullNumber(ticket.corrects().fullNumber());
            entity.setCorrectsIssuedOn(ticket.corrects().issuedOn());
            entity.setCorrectionReason(ticket.corrects().reason());
        }
        entity.setOrderIds(String.join(",", ticket.orderIds()));
        entity.setOrderNumbers(ticket.orderNumbers());
        entity.setTableName(ticket.tableName());
        entity.setPaymentReference(ticket.paymentReference());
        return entity;
    }

    private FiscalTicket toTicket(FiscalTicketEntity entity) {
        List<VatBreakdown.Line> vatLines = read(entity.getVatLines(), VAT_LINES);
        var vat = new VatBreakdown(vatLines, entity.getTotal(),
                vatLines.stream().map(VatBreakdown.Line::base).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add),
                entity.getVatTotal());
        var recipient = entity.getRecipientTaxId() == null ? null : new TicketRecipient(entity.getRecipientTaxId(),
                entity.getRecipientCountry(), entity.getRecipientName(), entity.getRecipientAddress());
        var corrects = entity.getCorrectsTicketId() == null ? null : new FiscalTicket.Correction(
                entity.getCorrectsTicketId(), entity.getCorrectsFullNumber(), entity.getCorrectsIssuedOn(),
                entity.getCorrectionReason());
        List<String> orderIds = entity.getOrderIds() == null || entity.getOrderIds().isBlank() ? List.of()
                : Arrays.asList(entity.getOrderIds().split(","));
        return new FiscalTicket(entity.getId(), TicketKind.valueOf(entity.getKind()), entity.getSeries(),
                entity.getNumber(), entity.getFullNumber(), entity.isTraining(),
                OffsetDateTime.of(entity.getIssuedLocal(), ZoneOffset.of(entity.getIssuedOffset())), entity.getIssuedBy(),
                new FiscalTicket.Issuer(entity.getIssuerLegalName(), entity.getIssuerTradeName(), entity.getIssuerTaxId(),
                        entity.getIssuerAddress(), entity.getIssuerPostalCode(), entity.getIssuerCity(),
                        entity.getIssuerProvince(), entity.getIssuerPhone()),
                recipient, read(entity.getLines(), LINES), entity.getDiscount(), entity.getTotal(), vat, entity.getTip(),
                PaymentMethod.valueOf(entity.getPaymentMethod()), entity.getCashReceived(), entity.getChangeGiven(),
                corrects, List.copyOf(orderIds), entity.getOrderNumbers(), entity.getTableName(),
                entity.getPaymentReference());
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot store ticket", e);
        }
    }

    private static <T> T read(String json, TypeReference<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read stored ticket", e);
        }
    }
}
