package es.brasatech.fastbite.email.service;

import es.brasatech.fastbite.application.mail.LocationLifecycleMailPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
public class LocationLifecycleMailAdapter implements LocationLifecycleMailPort {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm z")
            .withZone(MADRID);

    private final JavaMailSender sender;
    private final String from;
    private final AccountMailRenderer renderer;

    public LocationLifecycleMailAdapter(
            JavaMailSender sender,
            @Value("${spring.mail.username}") String from,
            AccountMailRenderer renderer) {
        this.sender = sender;
        this.from = from;
        this.renderer = renderer;
    }

    @Override
    public void sendLocationLifecycleNotice(
            String ownerEmail,
            String tenantId,
            Action action,
            Scope scope,
            Instant when,
            String language) {
        if (ownerEmail == null || ownerEmail.isBlank()) {
            return;
        }
        String lang = normalizeLanguage(language);
        String formattedWhen = DATE_TIME.format(when);
        String where = scopeLabel(scope, lang);
        String subject = buildSubject(tenantId, action, lang);
        String body = buildBody(tenantId, action, where, formattedWhen, lang);
        renderer.send(sender, from, ownerEmail, "location-lifecycle", lang, subject, null, body, body, null, null);
    }

    private static String normalizeLanguage(String language) {
        if (language == null) {
            return "es";
        }
        String lower = language.strip().toLowerCase();
        if (lower.startsWith("es")) {
            return "es";
        }
        if (lower.startsWith("pt")) {
            return "pt";
        }
        if (lower.startsWith("en")) {
            return "en";
        }
        return "es";
    }

    private static String scopeLabel(Scope scope, String lang) {
        return switch (lang) {
            case "es" -> switch (scope) {
                case SANDBOX -> "entorno de pruebas (sandbox)";
                case PRODUCTION -> "producción";
                case BOTH -> "producción y entorno de pruebas (sandbox)";
            };
            case "pt" -> switch (scope) {
                case SANDBOX -> "ambiente de testes (sandbox)";
                case PRODUCTION -> "produção";
                case BOTH -> "produção e ambiente de testes (sandbox)";
            };
            default -> switch (scope) {
                case SANDBOX -> "sandbox";
                case PRODUCTION -> "production";
                case BOTH -> "production and sandbox";
            };
        };
    }

    private static String buildSubject(String tenantId, Action action, String lang) {
        return switch (lang) {
            case "es" -> action == Action.DELETED
                    ? "FastBite — Restaurante eliminado: " + tenantId
                    : "FastBite — Restaurante cerrado: " + tenantId;
            case "pt" -> action == Action.DELETED
                    ? "FastBite — Restaurante eliminado: " + tenantId
                    : "FastBite — Restaurante encerrado: " + tenantId;
            default -> action == Action.DELETED
                    ? "FastBite — Restaurant deleted: " + tenantId
                    : "FastBite — Restaurant closed: " + tenantId;
        };
    }

    private static String buildBody(String tenantId, Action action, String where, String when, String lang) {
        return switch (lang) {
            case "es" -> action == Action.DELETED
                    ? "Confirmamos que tu restaurante '" + tenantId + "' ha sido eliminado de forma permanente.\n\n"
                    + "Entorno: " + where + "\n"
                    + "Fecha y hora: " + when + "\n\n"
                    + "Si no reconoces esta acción, inicia sesión en tu cuenta de propietario de FastBite y contacta con soporte."
                    : "Confirmamos que tu restaurante '" + tenantId + "' ha sido cerrado de forma permanente.\n\n"
                    + "Entorno: " + where + "\n"
                    + "Fecha y hora: " + when + "\n\n"
                    + "Los tickets emitidos y los registros VERI*FACTU siguen disponibles en tu consola de propietario durante el plazo legal de conservación.";
            case "pt" -> action == Action.DELETED
                    ? "Confirmamos que o teu restaurante '" + tenantId + "' foi eliminado permanentemente.\n\n"
                    + "Ambiente: " + where + "\n"
                    + "Data e hora: " + when + "\n\n"
                    + "Se não reconheces esta ação, inicia sessão na tua conta de proprietário FastBite e contacta o suporte."
                    : "Confirmamos que o teu restaurante '" + tenantId + "' foi encerrado permanentemente.\n\n"
                    + "Ambiente: " + where + "\n"
                    + "Data e hora: " + when + "\n\n"
                    + "Os talões emitidos e os registos VERI*FACTU permanecem disponíveis na tua consola de proprietário durante o período legal de conservação.";
            default -> action == Action.DELETED
                    ? "We confirm that your restaurant '" + tenantId + "' has been permanently deleted.\n\n"
                    + "Environment: " + where + "\n"
                    + "Date and time: " + when + "\n\n"
                    + "If you did not request this change, sign in to your FastBite owner console and contact support."
                    : "We confirm that your restaurant '" + tenantId + "' has been permanently closed.\n\n"
                    + "Environment: " + where + "\n"
                    + "Date and time: " + when + "\n\n"
                    + "Issued tickets and VERI*FACTU records remain available in your owner console for the statutory retention period.";
        };
    }
}
