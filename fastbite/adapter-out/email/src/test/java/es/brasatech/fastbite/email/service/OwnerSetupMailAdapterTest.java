package es.brasatech.fastbite.email.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import java.util.Properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OwnerSetupMailAdapterTest {
    @Test void sendsLocalizedPlainTextAndHtmlWithoutUnescapedUserInput() throws Exception {
        var sender = mock(JavaMailSender.class);
        var message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        new OwnerSetupMailAdapter(sender, "service@example.test").sendSetupLink("owner@example.test", "<owner>", "https://example.test/set-password?token=abc", "es");
        verify(sender).send(message);
        message.saveChanges();
        assertThat(message.getSubject()).contains("contraseña");
        var mixed = (jakarta.mail.Multipart) message.getContent();
        var related = (jakarta.mail.Multipart) mixed.getBodyPart(0).getContent();
        var alternative = (jakarta.mail.Multipart) related.getBodyPart(0).getContent();
        assertThat(alternative.getBodyPart(0).getContentType()).contains("text/plain");
        assertThat(alternative.getBodyPart(1).getContentType()).contains("text/html");
        assertThat((String) alternative.getBodyPart(1).getContent()).contains("&lt;owner&gt;");
    }
}
