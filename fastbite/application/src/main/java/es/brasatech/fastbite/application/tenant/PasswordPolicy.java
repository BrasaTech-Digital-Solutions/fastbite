package es.brasatech.fastbite.application.tenant;

import java.nio.charset.StandardCharsets;
import es.brasatech.fastbite.domain.exception.LocalizedRejection;

/** Password length policy shared by all account entry points. */
public final class PasswordPolicy {
    public static final String MESSAGE = "Use a password of at least 10 characters and at most 72 UTF-8 bytes.";
    private PasswordPolicy() { }

    public static void require(String password) {
        if (!isValid(password)) throw new LocalizedRejection("password.policy", MESSAGE);
    }

    public static boolean isValid(String password) {
        return password != null && password.codePointCount(0, password.length()) >= 10
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
