package es.brasatech.fastbite.email.service;

import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuestTicketMailAdapterTest {
    @Test void sendsOnlyTheFixedTicketContentInEachLanguageAndMarksPractice() throws Exception {
        for (String lang : new String[]{"en", "es", "pt"}) {
            for (boolean practice : new boolean[]{false, true}) {
                JavaMailSender sender = mock(JavaMailSender.class);
                MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
                when(sender.createMimeMessage()).thenReturn(message);
                var adapter = new GuestTicketMailAdapter(sender, "verified@example.test",
                        new AccountMailRenderer("FastBite company", "Address", "https://example.test"));
                String link = "https://fastbite.example.test/cafe/ticket/123?s=signed";
                adapter.sendTicket("guest@example.test", "Café\r\n\"Bcc: attacker@example.test\"", "<T-123>",
                        new BigDecimal("12.50"), link, practice, lang);
                verify(sender).send(message);
                message.saveChanges();
                InternetAddress address = (InternetAddress) message.getFrom()[0];
                assertThat(address.getAddress()).isEqualTo("verified@example.test");
                assertThat(address.getPersonal()).isEqualTo("CaféBcc: attacker@example.test").doesNotContain("\r", "\n", "\"");
                assertThat(message.getHeader("Bcc")).isNull();
                Multipart mixed = (Multipart) message.getContent();
                Multipart related = (Multipart) mixed.getBodyPart(0).getContent();
                Multipart alternative = (Multipart) related.getBodyPart(0).getContent();
                String plain = (String) alternative.getBodyPart(0).getContent();
                String html = (String) alternative.getBodyPart(1).getContent();
                assertThat(plain).contains("<T-123>", "12,50 €", link, "CaféBcc: attacker@example.test");
                assertThat(html).contains("&lt;T-123&gt;", link, "fastbite").doesNotContain("<T-123>", "FastBite account", "cuenta FastBite");
                if (practice) {
                    assertThat(message.getSubject()).isEqualTo(switch (lang) { case "es" -> "Ticket de prueba — sin validez fiscal"; case "pt" -> "Recibo de treino — sem validade fiscal"; default -> "Training ticket — not valid as an invoice"; });
                    assertThat(plain).contains(switch (lang) { case "es" -> "Este es un ticket de prueba y no tiene validez fiscal."; case "pt" -> "Este é um recibo de treino e não tem validade fiscal."; default -> "This is a training ticket and is not valid as an invoice."; });
                } else {
                    assertThat(message.getSubject()).doesNotContain("prueba", "treino", "Training");
                    assertThat(plain).doesNotContain("no tiene validez fiscal", "não tem validade fiscal", "not valid as an invoice");
                }
            }
        }
    }

    @Test void absentRestaurantNameFallsBackInFromAndLeadAndAmountsMatchTicketPage() throws Exception {
        for (String lang : new String[]{"en", "es", "pt"}) {
            JavaMailSender sender = mock(JavaMailSender.class);
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
            when(sender.createMimeMessage()).thenReturn(message);
            var adapter = new GuestTicketMailAdapter(sender, "verified@example.test",
                    new AccountMailRenderer("FastBite company", "Address", "https://example.test"));
            adapter.sendTicket("guest@example.test", null, "T-1", new BigDecimal("1234.50"),
                    "https://example.test/cafe/ticket/t?s=abc", false, lang);
            message.saveChanges();
            assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("FastBite");
            Multipart mixed = (Multipart) message.getContent();
            Multipart related = (Multipart) mixed.getBodyPart(0).getContent();
            Multipart alternative = (Multipart) related.getBodyPart(0).getContent();
            String plain = (String) alternative.getBodyPart(0).getContent();
            assertThat(plain).contains("FastBite", "1.234,50 €");
            assertThat(plain).contains(switch (lang) {
                case "es" -> "Aquí tienes el ticket de FastBite que has solicitado.";
                case "pt" -> "Aqui está o talão de FastBite que pediu.";
                default -> "Here is the ticket from FastBite that you requested.";
            });
        }
        assertThat(GuestTicketMailAdapter.safeDisplayName("\r\n\"''")).isEqualTo("FastBite");
    }
}
