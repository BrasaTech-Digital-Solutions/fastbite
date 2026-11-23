package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.domain.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

@ControllerAdvice
public class ResourceNotFoundAdvice {
    @ExceptionHandler(ResourceNotFoundException.class)
    public Object notFound(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
        boolean responseBody = handler instanceof HandlerMethod method
                && (method.hasMethodAnnotation(ResponseBody.class)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), RestController.class));
        String accept = request.getHeader("Accept");
        boolean json = responseBody || request.getRequestURI().contains("/api/")
                || (accept != null && accept.toLowerCase(Locale.ROOT).contains("application/json"))
                || "XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"));
        if (json) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Resource not found"));
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
        return null;
    }
}
