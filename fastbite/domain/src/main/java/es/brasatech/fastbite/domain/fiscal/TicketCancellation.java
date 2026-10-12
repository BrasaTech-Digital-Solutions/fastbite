package es.brasatech.fastbite.domain.fiscal;

import java.time.OffsetDateTime;

/**
 * A ticket issued by mistake, when there was no sale, and cancelled (anulada) on the day it was
 * issued. The ticket itself never changes: the cancellation is kept beside it, and its orders can
 * be charged again. A ticket for a sale that did happen is refunded instead.
 *
 * @param by     who cancelled it
 * @param reason why it was issued by mistake
 */
public record TicketCancellation(String ticketId, OffsetDateTime at, String by, String reason) {
}
