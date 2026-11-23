package es.brasatech.fastbite.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;

/**
 * Formats Spring Boot's {@code timestamp} error attribute in the page's language and {@code Europe/Madrid}
 * timezone so HTML error pages never render Java's default English {@link Date#toString()}.
 */
public class ErrorTimestampInterceptor implements HandlerInterceptor {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    private static final DateTimeFormatter EUROPEAN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(MADRID);
    private static final DateTimeFormatter ENGLISH = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss", Locale.ENGLISH).withZone(MADRID);

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
        if (modelAndView == null) {
            return;
        }
        Object raw = modelAndView.getModel().get("timestamp");
        Instant instant = toInstant(raw);
        if (instant == null) {
            return;
        }
        modelAndView.addObject("timestamp", format(instant, LocaleContextHolder.getLocale()));
    }

    static String format(Instant instant, Locale locale) {
        if (locale != null && "en".equalsIgnoreCase(locale.getLanguage())) {
            return ENGLISH.format(instant);
        }
        return EUROPEAN.format(instant);
    }

    private static Instant toInstant(Object raw) {
        if (raw instanceof Date date) {
            return date.toInstant();
        }
        if (raw instanceof Instant instant) {
            return instant;
        }
        if (raw instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (raw instanceof ZonedDateTime zdt) {
            return zdt.toInstant();
        }
        return null;
    }
}
