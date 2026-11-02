package es.brasatech.fastbite.application.mail;

import java.math.BigDecimal;

/** Sends a guest-requested ticket without retaining the destination address. */
public interface GuestTicketMailPort {
    void sendTicket(String recipient, String locationName, String ticketNumber, BigDecimal total,
                    String ticketUrl, boolean practice, String language);
}
