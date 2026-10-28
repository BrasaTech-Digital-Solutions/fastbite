package es.brasatech.fastbite.application.platform;

/**
 * Thrown when a promotion preview, confirm, or undo operation cannot be completed.
 * Carries a stable i18n code, an HTTP status code, and an English developer message for logging.
 */
public class PromotionException extends RuntimeException {

    private final String code;
    private final int statusCode;

    public PromotionException(String code, int statusCode, String message) {
        this(code, statusCode, message, null);
    }

    public PromotionException(String code, int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.statusCode = statusCode;
    }

    public String code() {
        return code;
    }

    public int statusCode() {
        return statusCode;
    }
}
