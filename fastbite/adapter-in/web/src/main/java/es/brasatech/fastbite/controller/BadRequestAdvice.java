package es.brasatech.fastbite.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A request the application rejects is answered with 400 and the reason, not a 500. */
@RestControllerAdvice
public class BadRequestAdvice {

    private final org.springframework.context.MessageSource messages;

    public BadRequestAdvice(org.springframework.context.MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String rejected(IllegalArgumentException rejection, java.util.Locale locale) {
        if (rejection instanceof es.brasatech.fastbite.domain.exception.LocalizedRejection localized) {
            return messages.getMessage(localized.key(), localized.args(), localized.getMessage(), locale);
        }
        return rejection.getMessage();
    }
}
