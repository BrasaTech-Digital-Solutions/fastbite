package es.brasatech.fastbite.email.service;

import es.brasatech.fastbite.application.mail.OwnerSetupMailPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class OwnerSetupMailAdapter implements OwnerSetupMailPort {
    private final JavaMailSender sender;
    private final String from;
    private final AccountMailRenderer renderer;
    public OwnerSetupMailAdapter(JavaMailSender sender, @Value("${spring.mail.username}") String from, AccountMailRenderer renderer) {
        this.sender = sender; this.from = from; this.renderer = renderer;
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
        String body = lead + "\n\n" + usernameLabel + ": " + username + "\n\n" + action + "\n\n" + note;
        send("owner-setup", email, subject, plain, body, link, actionLabel(language, "Set password", "Elegir contraseña", "Definir palavra-passe"), language);
    }
    @Override public void sendSignupVerification(String email, String username, String link, String language) {
        String subject, lead, action;
        switch (language == null ? "en" : language) {
            case "es" -> { subject = "Verifica tu correo de FastBite"; lead = "Confirma tu correo para crear tu restaurante."; action = "Verificar correo"; }
            case "pt" -> { subject = "Confirme o seu email FastBite"; lead = "Confirme o seu email para criar o seu restaurante."; action = "Confirmar email"; }
            default -> { subject = "Verify your FastBite email"; lead = "Verify your email to create your restaurant."; action = "Verify email"; }
        }
        send("signup-verification", email, subject, lead + "\n\n" + link, lead, link, action, language);
    }
    @Override public void sendOwnerVerification(String email, String username, String link, String language) {
        String text = switch (language == null ? "en" : language) {
            case "es" -> "Confirma tu correo para tu cuenta FastBite.";
            case "pt" -> "Confirme o email da sua conta FastBite.";
            default -> "Verify the email address for your FastBite account.";
        };
        send("owner-verification", email, "FastBite", text + "\n\n" + link, text, link, actionLabel(language, "Verify email", "Verificar correo", "Confirmar email"), language);
    }
    @Override public void sendOwnerEmailNotice(String email, String language) {
        String text = switch (language == null ? "en" : language) {
            case "es" -> "Se ha cambiado el correo de tu cuenta FastBite.";
            case "pt" -> "O email da sua conta FastBite foi alterado.";
            default -> "Your FastBite account email was changed.";
        };
        send("owner-email-notice", email, "FastBite", text, text, null, null, language);
    }
    @Override public void sendPasswordReset(String email, String username, String link, String language) {
        String subject, body;
        switch (language == null ? "en" : language) {
            case "es" -> { subject = "Restablece tu contraseña de FastBite"; body = "Usa este enlace para restablecer tu contraseña. Caduca en una hora. Si no lo solicitaste, ignora este correo."; }
            case "pt" -> { subject = "Redefina a sua palavra-passe FastBite"; body = "Use este link para redefinir a palavra-passe. Expira numa hora. Se não pediu, ignore este email."; }
            default -> { subject = "Reset your FastBite password"; body = "Use this link to reset your password. It expires in one hour. If you did not request it, ignore this email."; }
        }
        send("password-reset", email, subject, body + "\n\n" + link, body, link, actionLabel(language, "Reset password", "Restablecer contraseña", "Redefinir palavra-passe"), language);
    }
    @Override public void sendSignupNotice(String email, String language) {
        String subject, body;
        switch (language == null ? "en" : language) {
            case "es" -> { subject = "Intento de registro en FastBite"; body = "Alguien intentó registrarse con tu correo. Si no fuiste tú, puedes ignorar este mensaje."; }
            case "pt" -> { subject = "Tentativa de registo FastBite"; body = "Alguém tentou registar-se com o seu email. Se não foi você, pode ignorar esta mensagem."; }
            default -> { subject = "A FastBite signup attempt"; body = "Someone tried to sign up using your email. If this was not you, you can ignore this message."; }
        }
        send("signup-notice", email, subject, body, body, null, null, language);
    }
    private void send(String kind, String email, String subject, String plain, String body, String link, String action, String language) {
        String heading = switch (kind) {
            case "owner-verification" -> actionLabel(language, "Verify your email", "Verifica tu correo", "Confirme o seu email");
            case "owner-email-notice" -> actionLabel(language, "Your email was changed", "Tu correo ha cambiado", "O seu email foi alterado");
            default -> null;
        };
        renderer.send(sender, from, email, kind, language, subject, heading, plain, body, link, action);
    }
    private static String actionLabel(String language, String en, String es, String pt) {
        return switch (language == null ? "en" : language) { case "es" -> es; case "pt" -> pt; default -> en; };
    }
}
