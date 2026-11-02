package es.brasatech.fastbite.email.service;

import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.UnsupportedEncodingException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Renders mail independently of the web template engine, preserving a plain-text alternative. */
@Component
public class AccountMailRenderer {
    private final SpringTemplateEngine engine;
    private final String company;
    private final String address;
    private final String privacyUrl;

    public AccountMailRenderer(@Value("${fastbite.legal.company-name:BRASATECH DIGITAL SOLUTIONS, S.L.U.}") String company,
            @Value("${fastbite.legal.address:Calle Ganaderos, 24, bajo, 03130 Santa Pola (Alicante), España}") String address,
            @Value("${fastbite.legal.website:https://fastbite.brasatech.es}") String website) {
        this.company = company;
        this.address = address;
        this.privacyUrl = website.replaceAll("/+$", "") + "/legal/privacy";
        this.engine = new SpringTemplateEngine();
        ClassLoaderTemplateResolver html = new ClassLoaderTemplateResolver();
        html.setCharacterEncoding("UTF-8");
        html.setTemplateMode(TemplateMode.HTML);
        html.setResolvablePatterns(Set.of("*.html"));
        html.setOrder(1);
        ClassLoaderTemplateResolver text = new ClassLoaderTemplateResolver();
        text.setCharacterEncoding("UTF-8");
        text.setTemplateMode(TemplateMode.TEXT);
        text.setResolvablePatterns(Set.of("*.txt"));
        text.setOrder(2);
        engine.setTemplateResolvers(Set.of(html, text));
    }

    public void send(JavaMailSender sender, String from, String recipient, String kind, String language,
            String subject, String heading, String plain, String body, String link, String buttonLabel) {
        sendMessage(sender, from, null, recipient, kind, language, subject, heading, plain, body, link, buttonLabel, false);
    }

    /** Sends a guest-requested ticket using the same layout with a restaurant display name. */
    public void sendGuestTicket(JavaMailSender sender, String from, String displayName, String recipient, String language,
            String subject, String plain, String body, String link, String buttonLabel) {
        sendMessage(sender, from, displayName, recipient, "guest-ticket", language, subject, null, plain, body, link, buttonLabel, true);
    }

    private void sendMessage(JavaMailSender sender, String from, String displayName, String recipient, String kind,
            String language, String subject, String heading, String plain, String body, String link, String buttonLabel,
            boolean guest) {
        Locale locale = switch (language == null ? "en" : language) {
            case "es" -> Locale.forLanguageTag("es");
            case "pt" -> Locale.forLanguageTag("pt");
            default -> Locale.ENGLISH;
        };
        Context context = new Context(locale);
        context.setVariables(Map.of("subject", subject, "preheader", body, "plain", plain, "body", body,
                "buttonLabel", buttonLabel == null ? "" : buttonLabel, "company", company, "address", address,
                "privacyUrl", privacyUrl, "privacyLabel", label(locale, "Privacy", "Privacidad", "Privacidade"),
                "fallbackLabel", label(locale, "If the button does not work, use this link:", "Si el botón no funciona, usa este enlace:", "Se o botão não funcionar, use esta ligação:")));
        context.setVariable("heading", heading == null ? subject : heading);
        context.setVariable("link", link);
        context.setVariable("reason", guest
                ? label(locale, "You requested this ticket.", "Has solicitado este ticket.", "Pediu este talão.")
                : label(locale, "You received this message about your FastBite account.",
                    "Recibes este mensaje sobre tu cuenta FastBite.", "Recebeu esta mensagem sobre a sua conta FastBite."));
        String path = "templates/mail/" + kind;
        String text = engine.process(path + ".txt", context);
        String html = engine.process(path + ".html", context);
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            if (displayName == null || displayName.isBlank()) helper.setFrom(from);
            else helper.setFrom(from, displayName);
            helper.setTo(recipient); helper.setSubject(subject);
            helper.setText(text, html);
            sender.send(message);
        } catch (MessagingException | UnsupportedEncodingException error) {
            throw new MailPreparationException("Could not prepare account email", error);
        }
    }
    private static String label(Locale locale, String en, String es, String pt) {
        return switch (locale.getLanguage()) { case "es" -> es; case "pt" -> pt; default -> en; };
    }
}
