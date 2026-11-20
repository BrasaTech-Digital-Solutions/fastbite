package es.brasatech.fastbite.application.digitalsign;

/**
 * Typed error raised by {@link DigitalSignClient} when a Digital Sign API call fails.
 * Messages are always sanitized so secrets and signing URLs never leak.
 */
public class DigitalSignException extends RuntimeException {

    public enum Kind {
        BAD_REQUEST,
        UNAUTHORIZED,
        ALLOWANCE_EXCEEDED,
        NOT_FOUND,
        CONFLICT,
        GONE,
        VALIDATION_FAILED,
        SERVER_ERROR,
        NETWORK_ERROR,
        UNCONFIGURED,
        UNEXPECTED_RESPONSE
    }

    private final Kind kind;
    private final int httpStatus;
    private final String errorCode;
    private final String field;

    public DigitalSignException(Kind kind, int httpStatus, String errorCode, String field, String message) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.field = field;
    }

    public DigitalSignException(Kind kind, int httpStatus, String errorCode, String field, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.field = field;
    }

    public Kind getKind() {
        return kind;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getField() {
        return field;
    }
}
