package es.brasatech.fastbite.email.service;

import es.brasatech.fastbite.application.mail.GuestTicketMailPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Fixed-content delivery of a signed guest ticket; the address is never retained. */
@Component
public class GuestTicketMailAdapter implements GuestTicketMailPort {
    private final JavaMailSender sender;
    private final String from;
    private final AccountMailRenderer renderer;

    public GuestTicketMailAdapter(JavaMailSender sender, @Value("${spring.mail.username}") String from,
                                  AccountMailRenderer renderer) {
        this.sender = sender;
        this.from = from;
        this.renderer = renderer;
    }

    @Override
    public void sendTicket(String recipient, String locationName, String ticketNumber, BigDecimal total,
                           String ticketUrl, boolean practice, String language) {
        String name = safeDisplayName(locationName);
        String subject;
        String lead;
        String number;
        String amount;
        String button;
        switch (language == null ? "en" : language) {
            case "es" -> {
                subject = practice ? "Ticket de prueba — sin validez fiscal" : "Tu ticket";
                lead = (practice ? "Este es un ticket de prueba y no tiene validez fiscal.\n\n" : "")
                        + "Aquí tienes el ticket de " + name + " que has solicitado.";
                number = "Ticket"; amount = "Total"; button = "Ver y guardar el ticket";
            }
            case "pt" -> {
                subject = practice ? "Recibo de treino — sem validade fiscal" : "O seu talão";
                lead = (practice ? "Este é um recibo de treino e não tem validade fiscal.\n\n" : "")
                        + "Aqui está o talão de " + name + " que pediu.";
                number = "Talão"; amount = "Total"; button = "Ver e guardar o talão";
            }
            default -> {
                subject = practice ? "Training ticket — not valid as an invoice" : "Your ticket";
                lead = (practice ? "This is a training ticket and is not valid as an invoice.\n\n" : "")
                        + "Here is the ticket from " + name + " that you requested.";
                number = "Ticket"; amount = "Total"; button = "View and save ticket";
            }
        }
        // The ticket page uses a point for grouping and a comma for the decimal mark in all languages.
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setDecimalSeparator(',');
        symbols.setGroupingSeparator('.');
        DecimalFormat money = new DecimalFormat("#,##0.00", symbols);
        String body = lead + "\n\n" + number + ": " + ticketNumber + "\n" + amount + ": " + money.format(total) + " €";
        String plain = body + "\n\n" + ticketUrl;
        renderer.sendGuestTicket(sender, from, name, recipient, language, subject, plain, body, ticketUrl, button);
    }

    static String safeDisplayName(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[\\p{Cntrl}\"']", "").strip();
        if (cleaned.isBlank()) return "FastBite";
        return cleaned.length() <= 120 ? cleaned : cleaned.substring(0, 120);
    }
}
