package es.brasatech.fastbite.application.digitalsign;

/**
 * Response from {@code POST /requests/{id}/signers/{role}/signing-url}.
 *
 * <p>{@code signingUrl} is treated as a secret and redacted in {@link #toString()}.
 */
public record SigningUrlResponse(
        String requestId,
        String role,
        String signingUrl) {

    @Override
    public String toString() {
        return "SigningUrlResponse{"
                + "requestId='" + requestId + '\''
                + ", role='" + role + '\''
                + ", signingUrl=" + (signingUrl != null && !signingUrl.isBlank() ? "[REDACTED]" : "null")
                + '}';
    }
}
