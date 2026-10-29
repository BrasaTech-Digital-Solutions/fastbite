package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

/** Render localized domain rejections in form errors, retaining the English fallback. */
final class RejectionMessages {
    private RejectionMessages() { }
    static String resolve(Exception rejection, MessageSource messages) {
        if (rejection instanceof LocalizedRejection localized)
            return messages.getMessage(localized.key(), localized.args(), localized.getMessage(), LocaleContextHolder.getLocale());
        return rejection.getMessage();
    }
}
