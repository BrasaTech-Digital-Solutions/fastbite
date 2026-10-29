package es.brasatech.fastbite.email.service;

import es.brasatech.fastbite.application.mail.OwnerSetupMailPort;
import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class OwnerSetupMailAdapter implements OwnerSetupMailPort {
    private final JavaMailSender sender;
    private final String from;
    public OwnerSetupMailAdapter(JavaMailSender sender, @Value("${spring.mail.username}") String from) {
        this.sender = sender; this.from = from;
    }
    @Override public void sendSetupLink(String email, String username, String link) {
        sendSetupLink(email, username, link, "en");
    }
    @Override public void sendSetupLink(String email, String username, String link, String language) {
        String subject, lead, usernameLabel, action, note;
        switch (language == null ? "en" : language) {
            case "es" -> { subject = "Configura tu contraseña de FastBite"; lead = "Tu cuenta de propietario de FastBite está lista."; usernameLabel = "Usuario"; action = "Elige tu contraseña con este enlace de un solo uso (caduca en 24 horas):"; note = "Si no solicitaste esta cuenta, ignora este correo."; }
            case "pt" -> { subject = "Defina a sua palavra-passe FastBite"; lead = "A sua conta de proprietário FastBite está pronta."; usernameLabel = "Utilizador"; action = "Escolha a palavra-passe com este link de utilização única (expira em 24 horas):"; note = "Se não pediu esta conta, ignore este email."; }
            default -> { subject = "Set your FastBite password"; lead = "Your FastBite owner account is ready."; usernameLabel = "Username"; action = "Choose your password using this single-use link (expires in 24 hours):"; note = "If you did not request this account, ignore this email."; }
        }
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from); helper.setTo(email); helper.setSubject(subject);
            String plain = lead + "\n\n" + usernameLabel + ": " + username + "\n\n" + action + "\n" + link + "\n\n" + note;
            String html = "<p>" + escape(lead) + "</p><p>" + escape(usernameLabel) + ": " + escape(username)
                    + "</p><p>" + escape(action) + "<br><a href=\"" + escape(link) + "\">" + escape(link) + "</a></p><p>" + escape(note) + "</p>";
            helper.setText(plain, html);
            sender.send(message);
        } catch (MessagingException error) {
            throw new MailPreparationException("Could not prepare account email", error);
        }
    }
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
