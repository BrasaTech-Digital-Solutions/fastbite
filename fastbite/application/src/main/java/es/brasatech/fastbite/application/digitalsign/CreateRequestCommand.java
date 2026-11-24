package es.brasatech.fastbite.application.digitalsign;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Payload for {@code POST /requests}.
 *
 * <p>{@code requestedFiles} may be {@code null} (omitted from JSON to keep the template's default
 * requested files) or empty (serialized as {@code []} to request no files).
 */
public record CreateRequestCommand(
        String templateId,
        String title,
        String message,
        LocalDate expiresOn,
        String language,
        String externalReference,
        boolean sendEmail,
        List<SignerInput> signers,
        Map<String, String> values,
        List<RequestedFileInput> requestedFiles,
        String redirectUrl) {

    public CreateRequestCommand(
            String templateId,
            String title,
            String message,
            LocalDate expiresOn,
            String language,
            String externalReference,
            boolean sendEmail,
            List<SignerInput> signers,
            Map<String, String> values,
            List<RequestedFileInput> requestedFiles) {
        this(templateId, title, message, expiresOn, language, externalReference, sendEmail, signers, values, requestedFiles, null);
    }

    public CreateRequestCommand {
        signers = signers == null ? List.of() : List.copyOf(signers);
        values = values == null ? Map.of() : Map.copyOf(values);
        requestedFiles = requestedFiles == null ? null : List.copyOf(requestedFiles);
        redirectUrl = (redirectUrl == null || redirectUrl.isBlank()) ? null : redirectUrl.strip();
    }

    public CreateRequestCommand withoutRedirectUrl() {
        return new CreateRequestCommand(
                templateId, title, message, expiresOn, language, externalReference,
                sendEmail, signers, values, requestedFiles, null);
    }

    public record SignerInput(
            String role,
            String name,
            String email,
            String language,
            String signatureMethod,
            String nif) {
    }

    public record RequestedFileInput(
            String role,
            String label,
            boolean required) {
    }
}
