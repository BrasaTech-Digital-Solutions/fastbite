package es.brasatech.fastbite.application.tenant;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {
    @Test void rejectsWithTheSharedLocalizedKey() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> PasswordPolicy.require("short")))
                .isInstanceOf(es.brasatech.fastbite.domain.exception.LocalizedRejection.class)
                .satisfies(error -> assertThat(((es.brasatech.fastbite.domain.exception.LocalizedRejection) error).key()).isEqualTo("password.policy"));
    }

    @Test void enforcesMinimumAndUtf8ByteLimit() {
        assertThat(PasswordPolicy.isValid(null)).isFalse();
        assertThat(PasswordPolicy.isValid("123456789")).isFalse();
        assertThat(PasswordPolicy.isValid("1234567890")).isTrue();
        assertThat(PasswordPolicy.isValid("x".repeat(72))).isTrue();
        assertThat(PasswordPolicy.isValid("x".repeat(73))).isFalse();
        assertThat(PasswordPolicy.isValid("é".repeat(36))).isTrue();
        assertThat(PasswordPolicy.isValid("é".repeat(37))).isFalse();
    }
}
