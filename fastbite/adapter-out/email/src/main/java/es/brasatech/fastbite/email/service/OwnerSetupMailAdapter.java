package es.brasatech.fastbite.email.service;

import es.brasatech.fastbite.application.mail.OwnerSetupMailPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Locale;

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
    @Override public void sendVerifactuOnHold(String email, String tenantId, String link, String language) {
        sendVerifactuOnHold(email, tenantId, link, language, null);
    }
    @Override public void sendVerifactuOnHold(String email, String tenantId, String link, String language, String holdReason) {
        String lang = language == null ? "en" : language;
        String reasonLine = verifactuHoldReasonText(holdReason, lang);
        String subject, body, action;
        switch (lang) {
            case "es" -> {
                subject = "FastBite — " + tenantId + " está en pausa (autorización VERI*FACTU)";
                body = "Tu local \"" + tenantId + "\" no puede emitir tickets en este momento.\n\nMotivo: " + reasonLine
                        + "\n\nAbre el paso de VERI*FACTU en tu panel de propietario para revisarlo o completar la firma con tu certificado digital.";
                action = "Ir al paso de VERI*FACTU";
            }
            case "pt" -> {
                subject = "FastBite — " + tenantId + " está em pausa (autorização VERI*FACTU)";
                body = "O seu espaço \"" + tenantId + "\" não pode emitir talões neste momento.\n\nMotivo: " + reasonLine
                        + "\n\nAbra o passo VERI*FACTU no painel de proprietário para rever ou concluir a assinatura com o seu certificado digital.";
                action = "Ir para o passo VERI*FACTU";
            }
            default -> {
                subject = "FastBite — " + tenantId + " is on hold (VERI*FACTU authorisation)";
                body = "Your location \"" + tenantId + "\" cannot issue tickets right now.\n\nReason: " + reasonLine
                        + "\n\nOpen the VERI*FACTU step in your owner workspace to review it or complete signing with your digital certificate.";
                action = "Open VERI*FACTU step";
            }
        }
        send("verifactu-on-hold", email, subject, body + "\n\n" + link, body, link, action, lang);
    }
    private static String verifactuHoldReasonText(String holdReason, String language) {
        String code = holdReason == null ? "" : holdReason.strip().toUpperCase(Locale.ROOT);
        return switch (code) {
            case "COLLABORATION_INACTIVE" -> actionLabel(language,
                    "AEAT social collaboration is temporarily disabled.",
                    "La colaboración social con la AEAT está desactivada temporalmente.",
                    "A colaboração social com a AEAT está temporariamente desativada.");
            case "FISCAL_INCOMPLETE" -> actionLabel(language,
                    "Business details (legal name, NIF and address) are incomplete.",
                    "Faltan datos fiscales del negocio (razón social, NIF y dirección).",
                    "Faltam dados fiscais do negócio (denominação social, NIF e morada).");
            case "IN_PROGRESS" -> actionLabel(language,
                    "Waiting for the VERI*FACTU authorisation to be signed.",
                    "Esperando la firma de la autorización VERI*FACTU.",
                    "A aguardar a assinatura da autorização VERI*FACTU.");
            case "DOWNLOAD_FAILED" -> actionLabel(language,
                    "The signed VERI*FACTU documents have not been downloaded yet.",
                    "Aún no se han podido descargar los documentos firmados de VERI*FACTU.",
                    "Os documentos assinados de VERI*FACTU ainda não foram transferidos.");
            case "DECLINED" -> actionLabel(language,
                    "The VERI*FACTU signature request was declined.",
                    "La solicitud de firma VERI*FACTU fue rechazada.",
                    "O pedido de assinatura VERI*FACTU foi recusado.");
            case "EXPIRED" -> actionLabel(language,
                    "The VERI*FACTU signature request expired.",
                    "La solicitud de firma VERI*FACTU ha caducado.",
                    "O pedido de assinatura VERI*FACTU expirou.");
            case "CANCELLED" -> actionLabel(language,
                    "The VERI*FACTU signature request was cancelled.",
                    "La solicitud de firma VERI*FACTU fue cancelada.",
                    "O pedido de assinatura VERI*FACTU foi cancelado.");
            case "NIF_MISMATCH" -> actionLabel(language,
                    "The signing certificate NIF does not match the business NIF.",
                    "El NIF del certificado no coincide con el NIF del negocio.",
                    "O NIF do certificado não coincide com o NIF do negócio.");
            case "NIF_UNVERIFIED" -> actionLabel(language,
                    "Could not verify a Spanish NIF in the signing certificate.",
                    "No se pudo verificar un NIF español en el certificado utilizado.",
                    "Não foi possível verificar um NIF espanhol no certificado utilizado.");
            case "TEST_ENVIRONMENT" -> actionLabel(language,
                    "Signed in Digital Sign's test environment; not valid for production.",
                    "Firmada en el entorno de pruebas de Digital Sign; no vale para producción.",
                    "Assinada no ambiente de testes da Digital Sign; não é válida para produção.");
            case "DOCUMENTS_GONE" -> actionLabel(language,
                    "The signed documents are no longer available to download.",
                    "Los documentos firmados ya no están disponibles para descarga.",
                    "Os documentos assinados já não estão disponíveis para transferência.");
            case "REVOKED" -> actionLabel(language,
                    "The VERI*FACTU authorisation was revoked.",
                    "La autorización VERI*FACTU fue revocada.",
                    "A autorização VERI*FACTU foi revogada.");
            case "SIGNING_UNAVAILABLE" -> actionLabel(language,
                    "Electronic signing is not available yet.",
                    "La firma electrónica todavía no está disponible.",
                    "A assinatura eletrónica ainda não está disponível.");
            default -> actionLabel(language,
                    "The VERI*FACTU representation authorisation (Anexo I) is missing for the current NIF.",
                    "Falta una autorización de representación VERI*FACTU (Anexo I) válida para el NIF actual.",
                    "Falta uma autorização de representação VERI*FACTU (Anexo I) válida para o NIF atual.");
        };
    }
    @Override public void sendVerifactuOpenedWithTrial(String email, String tenantId, String link, String language) {
        String subject, body, action;
        switch (language == null ? "en" : language) {
            case "es" -> {
                subject = "FastBite — " + tenantId + " ya está abierto";
                body = "Tu local \"" + tenantId + "\" ya tiene su autorización VERI*FACTU válida y puede emitir tickets con normalidad. Tu periodo de prueba de 30 días ha comenzado.";
                action = "Abrir panel de propietario";
            }
            case "pt" -> {
                subject = "FastBite — " + tenantId + " já está aberto";
                body = "O seu espaço \"" + tenantId + "\" já tem a autorização VERI*FACTU válida e pode emitir talões normalmente. O seu período experimental de 30 dias começou.";
                action = "Abrir painel de proprietário";
            }
            default -> {
                subject = "FastBite — " + tenantId + " is now open";
                body = "Your location \"" + tenantId + "\" now has a valid VERI*FACTU authorisation and can issue tickets normally. Your 30-day free trial has started.";
                action = "Open owner workspace";
            }
        }
        send("verifactu-opened", email, subject, body + "\n\n" + link, body, link, action, language);
    }
    @Override public void sendVerifactuOpenedWithoutTrial(String email, String tenantId, String link, String language) {
        String subject, body, action;
        switch (language == null ? "en" : language) {
            case "es" -> {
                subject = "FastBite — " + tenantId + " ya está abierto";
                body = "Tu local \"" + tenantId + "\" ya tiene su autorización VERI*FACTU válida y puede emitir tickets con normalidad.";
                action = "Abrir panel de propietario";
            }
            case "pt" -> {
                subject = "FastBite — " + tenantId + " já está aberto";
                body = "O seu espaço \"" + tenantId + "\" já tem a autorização VERI*FACTU válida e pode emitir talões normalmente.";
                action = "Abrir painel de proprietário";
            }
            default -> {
                subject = "FastBite — " + tenantId + " is now open";
                body = "Your location \"" + tenantId + "\" now has a valid VERI*FACTU authorisation and can issue tickets normally.";
                action = "Open owner workspace";
            }
        }
        send("verifactu-opened", email, subject, body + "\n\n" + link, body, link, action, language);
    }
    @Override public void sendVerifactuReopened(String email, String tenantId, String link, String language) {
        String subject, body, action;
        switch (language == null ? "en" : language) {
            case "es" -> {
                subject = "FastBite — " + tenantId + " ya puede volver a emitir tickets";
                body = "Tu local \"" + tenantId + "\" vuelve a tener una autorización VERI*FACTU válida y ya puedes volver a emitir tickets con normalidad.";
                action = "Abrir panel de propietario";
            }
            case "pt" -> {
                subject = "FastBite — " + tenantId + " já pode voltar a emitir talões";
                body = "O seu espaço \"" + tenantId + "\" voltou a ter uma autorização VERI*FACTU válida e já pode voltar a emitir talões normalmente.";
                action = "Abrir painel de proprietário";
            }
            default -> {
                subject = "FastBite — " + tenantId + " can issue tickets again";
                body = "Your location \"" + tenantId + "\" has a valid VERI*FACTU authorisation again and you can issue tickets normally.";
                action = "Open owner workspace";
            }
        }
        send("verifactu-opened", email, subject, body + "\n\n" + link, body, link, action, language);
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
