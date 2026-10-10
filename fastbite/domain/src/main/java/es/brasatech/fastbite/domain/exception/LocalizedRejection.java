package es.brasatech.fastbite.domain.exception;

/**
 * A request the application refuses, with the key of the message that explains why in the user's
 * language. The exception message is the English text, for logs and for callers without messages.
 */
public class LocalizedRejection extends IllegalArgumentException {

    private final String key;
    private final transient Object[] args;

    public LocalizedRejection(String key, String english, Object... args) {
        super(english);
        this.key = key;
        this.args = args;
    }

    public String key() {
        return key;
    }

    public Object[] args() {
        return args.clone();
    }
}
