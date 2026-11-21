package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.verifactu.VerifactuAuthorizationRequiredException;
import es.brasatech.fastbite.domain.exception.ImageUploadRejection;
import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.HandlerMapping;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A request the application rejects is answered with 400 and the reason, not a 500. */
@RestControllerAdvice
public class BadRequestAdvice {

    private static final Logger log = LoggerFactory.getLogger(BadRequestAdvice.class);
    private final MessageSource messages;

    public BadRequestAdvice(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String rejected(IllegalArgumentException rejection, Locale locale) {
        if (rejection instanceof LocalizedRejection localized) {
            return messages.getMessage(localized.key(), localized.args(), localized.getMessage(), locale);
        }
        return rejection.getMessage();
    }

    @ExceptionHandler(VerifactuAuthorizationRequiredException.class)
    public ResponseEntity<Map<String, String>> verifactuAuthorizationRequired(
            VerifactuAuthorizationRequiredException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "error", exception.getMessage(),
                        "code", exception.code()));
    }

    @ExceptionHandler(ImageUploadRejection.class)
    public ResponseEntity<Map<String, String>> rejectedImage(ImageUploadRejection rejection, Locale locale) {
        log.warn("Rejected invalid image upload");
        String message = messages.getMessage(rejection.key(), rejection.args(), rejection.getMessage(), locale);
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    @ExceptionHandler({MultipartException.class,
            MissingServletRequestPartException.class})
    public ResponseEntity<?> invalidUpload(Exception exception,
            HttpServletRequest request, Locale locale) {
        // Do not log exception text: it can contain client-supplied multipart data.
        log.warn("Rejected malformed multipart request ({})", exception.getClass().getSimpleName());
        String message = messages.getMessage("upload.invalidMultipart", null,
                "Choose a file and submit a valid upload.", locale);
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        boolean responseBody = handler instanceof HandlerMethod method
                && (method.hasMethodAnnotation(ResponseBody.class)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), RestController.class));
        String accept = request.getHeader("Accept");
        boolean json = responseBody || request.getRequestURI().contains("/api/")
                || (accept != null && accept.toLowerCase(Locale.ROOT).contains("application/json"))
                || "XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"));
        if (json) return ResponseEntity.badRequest().body(Map.of("message", message));
        return ResponseEntity.badRequest()
                .contentType(MediaType.TEXT_PLAIN).body(message);
    }
}
