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
        String plain = lead + "\n\n" + usernameLabel + ": " + username + "\n\n" + action + "\n" + link + "\n\n" + note;
        String html = "<p>" + escape(lead) + "</p><p>" + escape(usernameLabel) + ": " + escape(username)
                + "</p><p>" + escape(action) + "<br><a href=\"" + escape(link) + "\">" + escape(link) + "</a></p><p>" + escape(note) + "</p>";
        send(email, subject, plain, html);
    }
    @Override public void sendSignupVerification(String email, String username, String link, String language) {
        String subject, lead, action;
        switch (language == null ? "en" : language) {
            case "es" -> { subject = "Verifica tu correo de FastBite"; lead = "Confirma tu correo para crear tu restaurante."; action = "Verificar correo"; }
            case "pt" -> { subject = "Confirme o seu email FastBite"; lead = "Confirme o seu email para criar o seu restaurante."; action = "Confirmar email"; }
            default -> { subject = "Verify your FastBite email"; lead = "Verify your email to create your restaurant."; action = "Verify email"; }
        }
        send(email, subject, lead + "\n\n" + link, "<p>" + escape(lead) + "</p><p><a href=\"" + escape(link) + "\">" + escape(action) + "</a></p>");
    }
    @Override public void sendOwnerVerification(String email, String username, String link, String language) {
        String text = switch (language == null ? "en" : language) {
            case "es" -> "Confirma tu correo para tu cuenta FastBite.";
            case "pt" -> "Confirme o email da sua conta FastBite.";
            default -> "Verify the email address for your FastBite account.";
        };
        send(email, "FastBite", text + "\n\n" + link, "<p>" + escape(text) + "</p><p><a href=\"" + escape(link) + "\">" + escape(link) + "</a></p>");
    }
    @Override public void sendOwnerEmailNotice(String email, String language) {
        String text = switch (language == null ? "en" : language) {
            case "es" -> "Se ha cambiado el correo de tu cuenta FastBite.";
            case "pt" -> "O email da sua conta FastBite foi alterado.";
            default -> "Your FastBite account email was changed.";
        };
        send(email, "FastBite", text, "<p>" + escape(text) + "</p>");
    }
    @Override public void sendSignupNotice(String email, String language) {
        String subject, body;
        switch (language == null ? "en" : language) {
            case "es" -> { subject = "Intento de registro en FastBite"; body = "Alguien intentó registrarse con tu correo. Si no fuiste tú, puedes ignorar este mensaje."; }
            case "pt" -> { subject = "Tentativa de registo FastBite"; body = "Alguém tentou registar-se com o seu email. Se não foi você, pode ignorar esta mensagem."; }
            default -> { subject = "A FastBite signup attempt"; body = "Someone tried to sign up using your email. If this was not you, you can ignore this message."; }
        }
        send(email, subject, body, "<p>" + escape(body) + "</p>");
    }
    private void send(String email, String subject, String plain, String html) {
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from); helper.setTo(email); helper.setSubject(subject);
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
