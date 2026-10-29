package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.application.tenant.PasswordPolicy;
import es.brasatech.fastbite.domain.exception.LocalizedRejection;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;
import java.util.Locale;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class RejectionMessagesTest {
    @Test void translatesTheSamePolicyRejectionForForms() {
        var messages = new StaticMessageSource();
        messages.addMessage("password.policy", Locale.of("es"), "Política de contraseña");
        LocaleContextHolder.setLocale(Locale.of("es"));
        try {
            var error = (LocalizedRejection) catchThrowable(() -> PasswordPolicy.require("short"));
            assertThat(RejectionMessages.resolve(error, messages)).isEqualTo("Política de contraseña");
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }
}
