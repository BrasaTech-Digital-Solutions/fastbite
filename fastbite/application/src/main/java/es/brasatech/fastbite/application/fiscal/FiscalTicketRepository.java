package es.brasatech.fastbite.application.fiscal;

import es.brasatech.fastbite.domain.fiscal.FiscalTicket;
import es.brasatech.fastbite.domain.fiscal.TicketCancellation;
import es.brasatech.fastbite.domain.fiscal.TicketKind;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** The tickets of the restaurant the current request is for. Tickets are only ever added. */
public interface FiscalTicketRepository {

    /**
     * Takes the next number of a series. The number stays reserved until the surrounding
     * transaction ends, so numbers are given out in order and without gaps.
     */
    int nextNumber(String series);

    /** Stores a new ticket. Saving a ticket that already exists is refused. */
    FiscalTicket add(FiscalTicket ticket);

    Optional<FiscalTicket> findById(String id);

    /** The ticket that charged for an order, if it was charged. */
    Optional<FiscalTicket> findSaleByOrderId(String orderId);

    /** The rectifying ticket or replacement issued for a ticket, if any. */
    Optional<FiscalTicket> findCorrection(String ticketId, TicketKind kind);

    /** Tickets issued in a period, oldest first. */
    List<FiscalTicket> findIssuedBetween(OffsetDateTime from, OffsetDateTime to);

    /** Every ticket ever issued by the restaurant, oldest first. */
    default List<FiscalTicket> findAll() {
        return List.of();
    }

    /**
     * Keeps the cancellation of a sale ticket issued by mistake and frees its orders, so they can
     * be charged again. The ticket itself is not changed.
     */
    void cancel(TicketCancellation cancellation);

    Optional<TicketCancellation> findCancellation(String ticketId);

    /** Cancellations made in a period, oldest first. */
    List<TicketCancellation> findCancellationsBetween(OffsetDateTime from, OffsetDateTime to);

    /** Whether any real (non-training) ticket was ever issued, which fixes the location's NIF and series. */
    boolean anyRealTicket();
}
